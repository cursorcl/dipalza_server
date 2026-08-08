# Configuración del servidor de despliegue (Windows)

Equivalente Windows de [server-setup.md](server-setup.md). El jar (Java 21 +
Spring Boot) corre igual en ambos sistemas operativos sin cambios de
código — lo que cambia acá es solo la capa de orquestación (servicio,
reverse proxy, scripts de deploy/rollback, permisos).

**Este documento describe la migración objetivo; no ha sido ejecutado ni
validado contra un servidor Windows real.** Antes de usarlo en producción,
seguirlo primero contra una VM Windows de prueba.

## 0. Requisitos previos

- Windows Server 2019 o superior (para poder habilitar el feature nativo
  OpenSSH Server del paso 5).
- Java 21 (mismo JDK que en Linux).
- [WinSW](https://github.com/winsw/winsw/releases) — envoltorio que corre
  cualquier ejecutable como Windows Service; reemplaza a systemd.
- [nginx para Windows](https://nginx.org/en/download.html) — se mantiene
  nginx como reverse proxy (en vez de migrar a IIS/ARR) para reutilizar la
  config TLS actual con la menor reescritura posible.

## 1. Crear la cuenta de servicio dedicada

Windows no tiene un equivalente directo a `sudoers` con una lista de
comandos exactos permitidos (ver `server-setup.md` paso 4). La alternativa
más cercana:

1. Crear un usuario local dedicado (`deploy-dipalza`) sin privilegios de
   administrador:
   ```powershell
   New-LocalUser -Name "deploy-dipalza" -NoPassword -AccountNeverExpires
   ```
2. Otorgarle el derecho **"Log on as a service"** (`SeServiceLogonRight`)
   vía `secpol.msc` → Local Policies → User Rights Assignment, o con
   `ntrights`/`Carbon`/`LocalAccounts` module.
3. Otorgarle permisos NTFS de escritura **solo** sobre `C:\dipalza-app\`
   (no sobre el resto del disco):
   ```powershell
   icacls "C:\dipalza-app" /grant "deploy-dipalza:(OI)(CI)M"
   ```
4. Para permitir arrancar/detener el servicio sin ser administrador local,
   otorgar permiso sobre el servicio específico (no sobre `services.msc`
   en general):
   ```powershell
   sc.exe sdset dipalza-app "D:(A;;RPWPCR;;;<SID-de-deploy-dipalza>)(A;;CCLCSWLOCRRC;;;IU)(A;;CCLCSWLOCRRC;;;SU)"
   ```
   (obtener el SID con `wmic useraccount where name='deploy-dipalza' get sid`).

## 2. Instalar Java 21 y WinSW

```powershell
# Java 21 (ejemplo con winget; también sirve el instalador MSI de Adoptium/Microsoft)
winget install EclipseAdoptium.Temurin.21.JDK

# WinSW
mkdir C:\dipalza-app
Invoke-WebRequest -Uri "https://github.com/winsw/winsw/releases/latest/download/WinSW-x64.exe" `
  -OutFile "C:\dipalza-app\dipalza-app.exe"
```

Copiar `scripts/windows/dipalza-app-winsw.xml` de este repo a
`C:\dipalza-app\dipalza-app.xml` (mismo nombre base que el `.exe`, es
como WinSW encuentra su config).

## 3. Estructura de carpetas y variables de entorno de máquina

```powershell
mkdir C:\dipalza-app\releases

# Secrets -- igual que en Linux, se setean en el entorno del servidor,
# NUNCA se versionan. /M los hace variables de MÁQUINA (visibles para
# el servicio de Windows, no solo para la sesión de usuario actual).
setx /M DB_PASSWORD "..."
setx /M JWT_SECRET "..."
setx /M FACTURACION_DB_PASSWORD "..."
setx /M MAIL_USERNAME "..."
setx /M MAIL_PASSWORD "..."
```

Reiniciar la sesión (o el servidor) para que las variables de máquina
queden visibles al instalar el servicio en el paso siguiente.

## 4. Migrar el jar actualmente en producción (si aplica)

Igual que el paso 6 de `server-setup.md`, pero con una **junction** en vez
de un symlink — una junction no requiere privilegio elevado ni "Developer
Mode" activado, a diferencia de `mklink /D` (symlink real):

```powershell
$version = "v1.2.2"  # tag real de la última GitHub Release
mkdir "C:\dipalza-app\releases\$version"
Copy-Item "C:\dipalza-app\dipalza.jar" "C:\dipalza-app\releases\$version\dipalza.jar"
New-Item -ItemType Junction -Path "C:\dipalza-app\current" -Target "C:\dipalza-app\releases\$version"
```

## 5. Registrar e iniciar el servicio Windows

```powershell
cd C:\dipalza-app
.\dipalza-app.exe install
.\dipalza-app.exe start
.\dipalza-app.exe status
```

Confirmar que responde: `Invoke-WebRequest http://localhost:8081/actuator/health`.

## 6. Reverse proxy (nginx para Windows)

Instalar el build oficial de nginx para Windows y reutilizar la config TLS
existente del servidor Linux (mismo rol: único punto de entrada público,
backend en `127.0.0.1:8081`, redirect HTTP→HTTPS para clientes móviles
legados en el puerto 8080). nginx para Windows corre como proceso, no
como servicio nativo — envolverlo también con WinSW (mismo patrón del
paso 2) si se quiere que arranque solo con el servidor.

## 7. Copiar los scripts de deploy y rollback

```powershell
# Desde tu máquina local, con OpenSSH Server ya habilitado en el paso 8
scp scripts\windows\deploy-remote.ps1 scripts\windows\rollback-remote.ps1 `
  deploy-dipalza@<host>:C:\dipalza-app\scripts\
```

Igual que en Linux, estos scripts no viajan versionados en cada release —
viven fijos en el servidor. Si cambian en el repo, hay que repetir este
paso a mano para actualizarlos ahí.

## 8. Habilitar OpenSSH Server (para reusar el workflow de GitHub Actions)

El `deploy.yml` actual usa `ssh`/`scp` puro (`webfactory/ssh-agent`).
Windows Server trae **OpenSSH Server** como feature opcional nativo desde
2019, lo que permite reusar ese mismo workflow casi sin cambios — evita
migrar todo el pipeline a WinRM.

```powershell
Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0
Start-Service sshd
Set-Service -Name sshd -StartupType Automatic
```

Autorizar la llave pública del deploy (mismo par de llaves ya generado
para Linux, o uno nuevo) en
`C:\Users\deploy-dipalza\.ssh\authorized_keys`.

Los comandos remotos que ejecuta `deploy.yml` (`mkdir`, `scp` del jar,
disparo del script de deploy) deben adaptarse por rama de SO en el
workflow: hoy asume `mkdir -p` y `.sh`, en Windows sería
`New-Item -Force` y `.ps1`. **No incluido en este PR** — el workflow
sigue apuntando a Linux hasta que se decida el corte real de migración;
este documento deja los artefactos listos para cuando corresponda
actualizarlo.

## 9. Rollback manual

```powershell
ssh deploy-dipalza@<host> "C:\dipalza-app\scripts\rollback-remote.ps1 -RawVersion 1.2.3"
```

## Resumen de equivalencias Linux → Windows

| Linux (actual)                         | Windows (este documento)                          |
|-----------------------------------------|----------------------------------------------------|
| `dipalza-app.service` (systemd)         | WinSW (`dipalza-app-winsw.xml`)                    |
| `Environment=` / `EnvironmentFile`      | Variables de máquina (`setx /M`) + `<env>` en WinSW |
| symlink (`ln -sfn`)                     | Junction (`New-Item -ItemType Junction`)            |
| `systemctl stop/start`                  | `Stop-Service`/`Start-Service`                      |
| `curl` (health check)                   | `Invoke-WebRequest`                                 |
| `sudoers` con comandos exactos          | ACL de servicio específico (`sc.exe sdset`)         |
| nginx (Linux)                           | nginx para Windows (mismo rol)                      |
| SSH del propio Linux                    | Feature OpenSSH Server de Windows                   |
