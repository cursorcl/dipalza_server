# Configuración inicial del servidor de despliegue

Pasos manuales de una sola vez, ejecutados como root (o con sudo) en el
servidor de producción, antes de que el workflow de deploy pueda usarse.

## 1. Crear el usuario dedicado para deploys

```bash
sudo useradd -r -m -s /bin/bash deploy-dipalza
```

## 2. Generar el par de llaves SSH (en tu máquina local, no en el servidor)

```bash
ssh-keygen -t ed25519 -f ./deploy_dipalza_key -N "" -C "github-actions-deploy"
```

La llave privada (`deploy_dipalza_key`) se carga como GitHub Secret
`DEPLOY_SSH_KEY` (Settings → Secrets and variables → Actions). Nunca se
commitea al repo.

## 3. Autorizar la llave pública en el servidor

```bash
sudo mkdir -p /home/deploy-dipalza/.ssh
sudo tee /home/deploy-dipalza/.ssh/authorized_keys < deploy_dipalza_key.pub
sudo chown -R deploy-dipalza:deploy-dipalza /home/deploy-dipalza/.ssh
sudo chmod 700 /home/deploy-dipalza/.ssh
sudo chmod 600 /home/deploy-dipalza/.ssh/authorized_keys
```

## 4. Dar permiso sudo sin contraseña, solo para los comandos exactos necesarios

```bash
sudo visudo -f /etc/sudoers.d/deploy-dipalza
```

Contenido del archivo (nombres de comando completos y fijos — nunca un
patrón genérico como `systemctl *`, para que este usuario no pueda tocar
ningún otro servicio del sistema):

```
deploy-dipalza ALL=(root) NOPASSWD: /usr/bin/systemctl stop dipalza-app.service, /usr/bin/systemctl start dipalza-app.service, /usr/bin/systemctl restart dipalza-app.service, /usr/bin/systemctl status dipalza-app.service
```

Verificar la sintaxis antes de salir de `visudo` (lo hace automáticamente
al guardar; si reporta un error, no guarda el archivo).

## 5. Dar al usuario la propiedad de la carpeta de despliegue

```bash
sudo mkdir -p /opt/dipalza-app/releases
sudo chown -R deploy-dipalza:deploy-dipalza /opt/dipalza-app
```

## 6. Migrar el jar actualmente en producción a la nueva estructura

Sustituir `<version-actual>` por la versión que está corriendo hoy (ver
`dipalza/pom.xml` o la última GitHub Release):

```bash
sudo -u deploy-dipalza mkdir -p /opt/dipalza-app/releases/<version-actual>
sudo -u deploy-dipalza cp /opt/dipalza-app/dipalza.jar /opt/dipalza-app/releases/<version-actual>/dipalza.jar
sudo -u deploy-dipalza ln -sfn /opt/dipalza-app/releases/<version-actual> /opt/dipalza-app/current
```

## 7. Actualizar el `ExecStart` del `.service` para apuntar al symlink

Editar el unit file de `dipalza-app.service` (típicamente
`/etc/systemd/system/dipalza-app.service`) y cambiar la línea `ExecStart`
a:

```ini
ExecStart=/usr/bin/java -jar /opt/dipalza-app/current/dipalza.jar
```

Luego:

```bash
sudo systemctl daemon-reload
sudo systemctl restart dipalza-app.service
sudo systemctl status dipalza-app.service
```

Confirmar que el servicio sigue respondiendo (`curl http://localhost:8080/actuator/health`)
antes de continuar.

## 8. Copiar el script de deploy al servidor

Una vez completada la Task 2 de este plan (que crea `scripts/deploy-remote.sh`
en el repo), copiarlo al servidor:

```bash
scp scripts/deploy-remote.sh deploy-dipalza@<host>:/opt/dipalza-app/scripts/deploy-remote.sh
ssh deploy-dipalza@<host> chmod +x /opt/dipalza-app/scripts/deploy-remote.sh
```

Este script no viaja versionado en cada release — vive fijo en el
servidor. Si el script cambia en el repo, hay que repetir este paso a
mano para actualizarlo ahí.

## 9. Cargar los secrets en GitHub

Settings → Secrets and variables → Actions → New repository secret:

- `DEPLOY_SSH_KEY`: contenido completo de la llave privada generada en el paso 2
- `DEPLOY_SSH_HOST`: hostname o IP del servidor
- `DEPLOY_SSH_USER`: `deploy-dipalza`

## 10. Rollback manual (si un deploy queda en mal estado)

El pipeline no hace rollback automático. Las versiones viejas quedan en
`/opt/dipalza-app/releases/` (las últimas 3). Para volver a la anterior:

```bash
ssh deploy-dipalza@<host> "ln -sfn /opt/dipalza-app/releases/<version-anterior> /opt/dipalza-app/current"
ssh deploy-dipalza@<host> "sudo systemctl restart dipalza-app.service"
```
