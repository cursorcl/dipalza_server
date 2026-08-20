<#
.SYNOPSIS
    Ejecuta el paquete deploy_desde_cero (00->08) de dipalza_server contra una
    instancia SQL Server local en Windows.

.DESCRIPTION
    Corre los 9 scripts en el orden correcto, se detiene ante el primer error
    (no sigue con pasos a medias), pide las contraseñas de forma segura
    (SecureString, nunca quedan en el historial de PowerShell en texto plano),
    y hace explícitos los dos pasos que requieren criterio humano:
      - Paso 06: elegir el código de lista de precio ACTIVA real (no adivinar).
      - Confirmación manual antes de 03 (crea triggers sobre tablas del ERP en
        vivo) y antes de 08 (habilita los jobs del Agent).

.PARAMETER ServerInstance
    Instancia SQL Server destino. Default: localhost (asume que corre en esta
    misma máquina). Usa "localhost\NOMBRE_INSTANCIA" si es una instancia con
    nombre, o "localhost,PUERTO" si escucha en un puerto no estándar.

.PARAMETER ScriptsPath
    Carpeta donde están los 9 archivos 00_..sql a 08_..sql. Default: la carpeta
    actual.

.PARAMETER BackupPath
    Ruta donde se guarda el respaldo de Mastersoft ANTES de tocar nada.
    Default: C:\respaldos\Mastersoft_antes_deploy.bak

.EXAMPLE
    .\deploy-desde-cero.ps1 -ServerInstance "localhost" -ScriptsPath "C:\dipalza\deploy_desde_cero"
#>

param(
    [string]$ServerInstance = "localhost",
    [string]$ScriptsPath = ".",
    [string]$BackupPath = "C:\respaldos\Mastersoft_antes_deploy.bak"
)

$ErrorActionPreference = "Stop"

function ConvertFrom-SecureStringPlain {
    param([SecureString]$Secure)
    $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secure)
    try {
        return [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
    } finally {
        [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
}

function Invoke-DeployStep {
    param(
        [string]$Description,
        [string]$InputFile = $null,
        [string]$Query = $null,
        [string]$StdinContent = $null
    )
    Write-Host ""
    Write-Host "=== $Description ===" -ForegroundColor Cyan

    $sqlArgs = @("-S", $ServerInstance, "-U", "sa", "-P", $saPasswordPlain, "-C", "-b")
    if ($InputFile) { $sqlArgs += @("-i", $InputFile) }
    if ($Query)      { $sqlArgs += @("-Q", $Query) }

    if ($StdinContent) {
        $StdinContent | sqlcmd @sqlArgs
    } else {
        sqlcmd @sqlArgs
    }

    if ($LASTEXITCODE -ne 0) {
        Write-Host ""
        Write-Host "DETENIDO: '$Description' terminó con error (exit code $LASTEXITCODE)." -ForegroundColor Red
        Write-Host "Revisa el mensaje de arriba. No se ejecutan los pasos siguientes." -ForegroundColor Red
        exit 1
    }
    Write-Host "OK: $Description" -ForegroundColor Green
}

function Confirm-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host $Message -ForegroundColor Yellow
    $resp = Read-Host "Escribe 'si' para continuar, cualquier otra cosa para abortar"
    if ($resp -ne "si") {
        Write-Host "Abortado por el usuario." -ForegroundColor Red
        exit 1
    }
}

# ---------------------------------------------------------------------------
# 0. Credenciales (nunca quedan en texto plano en el historial de PowerShell)
# ---------------------------------------------------------------------------
$saPasswordSecure = Read-Host "Clave de 'sa'" -AsSecureString
$saPasswordPlain = ConvertFrom-SecureStringPlain $saPasswordSecure

$dipalzaAppPasswordSecure = Read-Host "Clave REAL para el login 'dipalza_app' (min 8 chars, 3 de: mayus/minus/digito/simbolo)" -AsSecureString
$dipalzaAppPasswordPlain = ConvertFrom-SecureStringPlain $dipalzaAppPasswordSecure

if ($dipalzaAppPasswordPlain.Length -lt 8) {
    Write-Host "La clave de dipalza_app debe tener al menos 8 caracteres. Abortando." -ForegroundColor Red
    exit 1
}

Set-Location $ScriptsPath

# ---------------------------------------------------------------------------
# 1. Respaldo de Mastersoft ANTES de tocar nada (red de seguridad)
# ---------------------------------------------------------------------------
Confirm-Step "Se va a respaldar [Mastersoft] en $BackupPath antes de continuar."
New-Item -ItemType Directory -Force -Path (Split-Path $BackupPath) | Out-Null
Invoke-DeployStep -Description "Backup de Mastersoft" `
    -Query "BACKUP DATABASE Mastersoft TO DISK = N'$BackupPath' WITH INIT, STATS = 10;"

# ---------------------------------------------------------------------------
# 2. 00_crear_base_datos.sql (sustituye el placeholder de clave al vuelo,
#    nunca se escribe la clave real en el archivo del repo)
# ---------------------------------------------------------------------------
$contenido00 = Get-Content ".\00_crear_base_datos.sql" -Raw
$contenido00 = $contenido00 -replace 'CAMBIAR_ESTA_CLAVE', $dipalzaAppPasswordPlain
Invoke-DeployStep -Description "00 - Crear base [ventas] + login dipalza_app" -StdinContent $contenido00

# ---------------------------------------------------------------------------
# 3. 01, 02 - esquema y fuente de precios
# ---------------------------------------------------------------------------
Invoke-DeployStep -Description "01 - Esquema de ventas" -InputFile ".\01_esquema_ventas.sql"
Invoke-DeployStep -Description "02 - Fuente ListaPrecioActiva" -InputFile ".\02_listaprecioactiva_fuente.sql"

# ---------------------------------------------------------------------------
# 4. 03 - Colas y TRIGGERS sobre tablas reales del ERP (paso sensible)
# ---------------------------------------------------------------------------
Confirm-Step "El paso 03 crea 7 triggers AFTER INSERT/UPDATE/DELETE sobre tablas de Mastersoft EN VIVO (invdetallepartes, detalledocumento, encabezadocumento, msosttablas, PRECIOS). Desde este punto, cada operación normal del ERP dispara esta lógica extra."
Invoke-DeployStep -Description "03 - Colas y triggers en Mastersoft" -InputFile ".\03_colas_triggers_mastersoft.sql"

Write-Host ""
Write-Host "Verificando que los 7 triggers quedaron creados:" -ForegroundColor Cyan
Invoke-DeployStep -Description "Verificación de triggers" `
    -Query "USE Mastersoft; SELECT name FROM sys.triggers WHERE name LIKE 'trg_%';"

# ---------------------------------------------------------------------------
# 5. 04, 05 - procesadores y jobs (creados deshabilitados)
# ---------------------------------------------------------------------------
Invoke-DeployStep -Description "04 - Procesadores de ventas" -InputFile ".\04_procesadores_ventas.sql"
Invoke-DeployStep -Description "05 - Jobs del Agent (deshabilitados)" -InputFile ".\05_jobs_msdb.sql"

# ---------------------------------------------------------------------------
# 6. 06 - PASO MANUAL: elegir la lista de precio activa REAL
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "=== 06 - Configuración inicial: lista de precio activa ===" -ForegroundColor Cyan
Write-Host "Códigos de lista disponibles en Mastersoft (no elijas solo por volumen de filas - confirma con el negocio cuál es la ACTIVA hoy):" -ForegroundColor Yellow
sqlcmd -S $ServerInstance -U sa -P $saPasswordPlain -C -Q `
    "USE Mastersoft; SELECT DISTINCT CodigoLista, COUNT(*) OVER (PARTITION BY CodigoLista) AS n FROM dbo.PRECIOS ORDER BY n DESC;"

$codigoLista = Read-Host "Código de la lista de precio PRINCIPAL a sembrar (Rol='P')"
Invoke-DeployStep -Description "06 - Siembra ListaPrecioActiva('P', '$codigoLista')" `
    -Query "USE ventas; INSERT INTO dbo.ListaPrecioActiva (Rol, CodigoLista) VALUES ('P', '$codigoLista');"

$sembrarSecundaria = Read-Host "¿Hay también lista secundaria (PrecioLista2)? Escribe el código, o deja vacío si no aplica"
if ($sembrarSecundaria) {
    Invoke-DeployStep -Description "06 - Siembra ListaPrecioActiva('S', '$sembrarSecundaria')" `
        -Query "USE ventas; INSERT INTO dbo.ListaPrecioActiva (Rol, CodigoLista) VALUES ('S', '$sembrarSecundaria');"
}

# ---------------------------------------------------------------------------
# 7. 07 - poblado inicial masivo
# ---------------------------------------------------------------------------
Invoke-DeployStep -Description "07 - Poblado inicial de ventas" -InputFile ".\07_poblado_inicial_ventas.sql"

Write-Host ""
Write-Host "Conteos finales (compáralos contra lo que esperas del negocio real):" -ForegroundColor Cyan
sqlcmd -S $ServerInstance -U sa -P $saPasswordPlain -C -Q `
    "USE ventas; SELECT 'ruta' t,COUNT(*) n FROM dbo.ruta UNION ALL SELECT 'condicionventa',COUNT(*) FROM dbo.condicionventa UNION ALL SELECT 'cliente',COUNT(*) FROM dbo.cliente UNION ALL SELECT 'vendedor',COUNT(*) FROM dbo.vendedor UNION ALL SELECT 'producto',COUNT(*) FROM dbo.producto UNION ALL SELECT 'numerados',COUNT(*) FROM dbo.numerados UNION ALL SELECT 'producto_con_precio',COUNT(*) FROM dbo.producto WHERE VentaNeto<>0;"

# ---------------------------------------------------------------------------
# 8. 08 - habilita los 4 jobs (paso sensible: uno corre contra Mastersoft)
# ---------------------------------------------------------------------------
Confirm-Step "El paso 08 habilita los 4 jobs recurrentes del Agent (cada 15s-1min), incluido el que sincroniza contra Mastersoft en vivo."
Invoke-DeployStep -Description "08 - Habilitar jobs" -InputFile ".\08_habilitar_jobs.sql"

Write-Host ""
Write-Host "Esperando ~70s para que los 4 jobs alcancen a correr al menos una vez..." -ForegroundColor Cyan
Start-Sleep -Seconds 70

Write-Host "Estado de los jobs:" -ForegroundColor Cyan
sqlcmd -S $ServerInstance -U sa -P $saPasswordPlain -C -Q `
    "SELECT j.name AS Job, CASE h.run_status WHEN 0 THEN 'FAILED' WHEN 1 THEN 'OK' WHEN 2 THEN 'Retry' WHEN 3 THEN 'Cancelled' WHEN 4 THEN 'Running' ELSE 'Sin ejecutar aun' END AS Estado FROM msdb.dbo.sysjobs j LEFT JOIN msdb.dbo.sysjobhistory h ON h.job_id=j.job_id AND h.instance_id=(SELECT MAX(instance_id) FROM msdb.dbo.sysjobhistory WHERE job_id=j.job_id) WHERE j.name LIKE 'Dipalza%';"

Write-Host ""
Write-Host "DEPLOY DESDE CERO COMPLETO." -ForegroundColor Green
Write-Host "Pendiente: configurar DB_PASSWORD / FACTURACION_DB_PASSWORD (misma clave que dipalza_app) en el servidor donde corra la app dipalza_server." -ForegroundColor Yellow
