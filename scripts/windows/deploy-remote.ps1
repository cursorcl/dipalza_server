#Requires -Version 5.1
<#
Equivalente Windows de scripts/deploy-remote.sh. Diferencias frente al
original bash, todas forzadas por el SO (ver docs/deploy/server-setup-windows.md):

- Stop-Service/Start-Service en vez de systemctl.
- Junction (New-Item -ItemType Junction) en vez de symlink (ln -sfn): una
  junction no requiere privilegio elevado ni "Developer Mode" activado,
  a diferencia de un symlink real de directorio en Windows.
- Invoke-WebRequest en vez de curl para el health check.
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$Version
)

$ErrorActionPreference = 'Stop'

$Base = if ($env:DEPLOY_BASE) { $env:DEPLOY_BASE } else { 'C:\dipalza-app' }
$ServiceName = if ($env:DEPLOY_SERVICE) { $env:DEPLOY_SERVICE } else { 'dipalza-app' }
$HealthUrl = if ($env:DEPLOY_HEALTH_URL) { $env:DEPLOY_HEALTH_URL } else { 'http://localhost:8081/actuator/health' }
$HealthRetries = if ($env:DEPLOY_HEALTH_RETRIES) { [int]$env:DEPLOY_HEALTH_RETRIES } else { 60 }
$KeepReleases = 3

$ReleaseDir = Join-Path $Base "releases\$Version"
$LiveLink = Join-Path $Base 'current'

if (-not (Test-Path (Join-Path $ReleaseDir 'dipalza.jar'))) {
    Write-Error "No existe $ReleaseDir\dipalza.jar"
    exit 1
}

Stop-Service -Name $ServiceName

# El servicio ya está detenido, así que no hace falta un truco de junction
# temporal + move para atomicidad frente a tráfico en vivo. Remove-Item
# sobre una junction borra solo el enlace, no el contenido apuntado.
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
    Write-Error "El servicio no respondió en $HealthUrl tras el deploy."
    exit 1
}

Write-Host "Servicio arriba y respondiendo en $HealthUrl."

# Refresca el LastWriteTime de la versión recién desplegada antes de podar
# (mismo motivo que en deploy-remote.sh: evita podar la versión que
# está sirviendo tráfico si se redespliega una que ya existía).
(Get-Item $ReleaseDir).LastWriteTime = Get-Date

$releasesDir = Join-Path $Base 'releases'
Get-ChildItem -Path $releasesDir -Directory |
    Sort-Object LastWriteTime -Descending |
    Select-Object -Skip $KeepReleases |
    ForEach-Object { Remove-Item -Path $_.FullName -Recurse -Force }

Write-Host "Deploy de la versión $Version completado."
