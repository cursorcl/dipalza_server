#Requires -Version 5.1
<#
Equivalente Windows de scripts/rollback-remote.sh. Ver deploy-remote.ps1
para el detalle de las diferencias frente al script bash original.
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$RawVersion
)

$ErrorActionPreference = 'Stop'

# Recibe el número de versión sin el prefijo 'v' (más cómodo de teclear a
# mano en una emergencia); las carpetas de release sí usan el tag completo
# de la GitHub Release (con 'v'), así que se antepone acá -- mismo criterio
# que rollback-remote.sh.
if ($RawVersion -notmatch '^\d+\.\d+\.\d+(-[A-Za-z0-9.]+)?$') {
    Write-Error "version '$RawVersion' no tiene un formato válido (esperado algo como 1.2.3, sin 'v', opcionalmente con sufijo -algo)"
    exit 1
}

$Version = "v$RawVersion"
$Base = if ($env:DEPLOY_BASE) { $env:DEPLOY_BASE } else { 'C:\dipalza-app' }
$ServiceName = if ($env:DEPLOY_SERVICE) { $env:DEPLOY_SERVICE } else { 'dipalza-app' }
$HealthUrl = if ($env:DEPLOY_HEALTH_URL) { $env:DEPLOY_HEALTH_URL } else { 'http://localhost:8081/actuator/health' }
$HealthRetries = if ($env:DEPLOY_HEALTH_RETRIES) { [int]$env:DEPLOY_HEALTH_RETRIES } else { 60 }

$ReleaseDir = Join-Path $Base "releases\$Version"
$LiveLink = Join-Path $Base 'current'

if (-not (Test-Path (Join-Path $ReleaseDir 'dipalza.jar'))) {
    Write-Error "No existe $ReleaseDir\dipalza.jar (¿la versión $Version nunca se desplegó, o ya fue podada por la retención de últimas 3?)"
    exit 1
}

Stop-Service -Name $ServiceName

if (Test-Path $LiveLink) {
    (Get-Item $LiveLink).Delete()
}
New-Item -ItemType Junction -Path $LiveLink -Target $ReleaseDir | Out-Null

Start-Service -Name $ServiceName

$healthy = $false
for ($i = 0; $i -lt $HealthRetries; $i++) {
    try {
        $resp = Invoke-WebRequest -Uri $HealthUrl -UseBasicParsing -TimeoutSec 5
        if ($resp.StatusCode -eq 200) {
            $healthy = $true
            break
        }
    } catch {
        # Aún no responde -- reintenta hasta agotar HealthRetries.
    }
    Start-Sleep -Seconds 1
}

if (-not $healthy) {
    Write-Error "El servicio no respondió en $HealthUrl tras el rollback."
    exit 1
}

Write-Host "Rollback a la versión $Version completado y respondiendo en $HealthUrl."
