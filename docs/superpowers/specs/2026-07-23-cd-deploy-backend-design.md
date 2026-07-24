# Despliegue continuo del backend a servidor remoto — Diseño

## Objetivo

Poder desplegar una versión ya publicada (GitHub Release) de `dipalza_server` al servidor de producción del usuario, sin downtime evitable y con posibilidad de rollback manual a una versión anterior. El deploy no se dispara solo al publicarse una release — el usuario no siempre está disponible para aprobar en el momento, así que el disparo mismo del workflow (manual, eligiendo la versión) es el único gate. No hay aprobación pendiente ni espera: si nadie lo corre, no pasa nada.

## Alcance

Solo el backend (`dipalza_server`). El JAR de Spring Boot ya incluye los estáticos del frontend (Angular) empaquetados — no existe un despliegue separado para `dipalza_web_client` en este diseño.

## Arquitectura

```
El usuario decide desplegar (cuando quiera, no hay disparo automático)
        │
        ▼
Corre manualmente el workflow "Deploy" (workflow_dispatch),
indicando el tag/versión de la Release a desplegar (ej. "1.2.4")
        │
        ▼
1. Verifica que existe una Release con ese tag y descarga su .jar adjunto
2. SCP del .jar al servidor → /opt/dipalza-app/releases/<version>/dipalza.jar
3. SSH al servidor, ejecuta scripts/deploy-remote.sh <version>:
     a. systemctl stop dipalza-app.service   (sudo NOPASSWD)
     b. swap atómico del symlink: current -> releases/<version>
     c. systemctl start dipalza-app.service  (sudo NOPASSWD)
     d. health check (curl a un endpoint local)
     e. prunea releases/ dejando solo las últimas 3
        │
        ▼
Reporta éxito/fracaso en el resumen del job de GitHub Actions
```

## Componentes

### 1. Workflow `.github/workflows/deploy.yml` (nuevo)

- Trigger: `on: workflow_dispatch` con un input requerido `version` (string, ej. "1.2.4" — el tag de la GitHub Release a desplegar). Se corre a mano desde la pestaña Actions (o `gh workflow run deploy.yml -f version=1.2.4`) cuando el usuario decida, no hay disparo automático ni espera de aprobación.
- Pasos:
  1. Verificar que existe una GitHub Release con tag `${{ inputs.version }}` y descargar su asset `dipalza-*.jar` vía `gh release download`, con `GITHUB_TOKEN`. Si no existe esa release, el job falla inmediatamente con un mensaje claro (antes de tocar el servidor).
  2. Configurar el agente SSH con la llave privada (`secrets.DEPLOY_SSH_KEY`).
  3. `scp` del jar descargado a `deploy-dipalza@<host>:/opt/dipalza-app/releases/<version>/dipalza.jar` (crea la carpeta remota primero con `ssh ... mkdir -p`).
  4. `ssh deploy-dipalza@<host> '/opt/dipalza-app/scripts/deploy-remote.sh <version>'` — ejecuta el script ya presente en el servidor (ver componente 2), no un script que viaje en cada corrida.
  5. Si el script falla (exit code ≠ 0), el job falla y el usuario lo ve en GitHub Actions.
- Secrets nuevos requeridos en GitHub: `DEPLOY_SSH_KEY` (privada), `DEPLOY_SSH_HOST`, `DEPLOY_SSH_USER` (o hardcodear `deploy-dipalza` si no cambia nunca).
- Quién puede correrlo: `workflow_dispatch` ya está restringido por GitHub a colaboradores con permiso de escritura en el repo — no se necesita un Environment con "required reviewer" adicional, porque el disparo manual mismo cumple ese rol de gate.

### 2. Script `scripts/deploy-remote.sh` (nuevo, vive en el servidor, no en el repo — ver Prerrequisitos)

Recibe `<version>` como argumento. Asume que el `.jar` ya fue copiado a `releases/<version>/dipalza.jar` por el paso de SCP.

```bash
#!/usr/bin/env bash
set -euo pipefail

VERSION="$1"
BASE=/opt/dipalza-app
RELEASE_DIR="$BASE/releases/$VERSION"
LIVE_LINK="$BASE/current"
TMP_LINK="$BASE/current_tmp"

if [ ! -f "$RELEASE_DIR/dipalza.jar" ]; then
  echo "ERROR: no existe $RELEASE_DIR/dipalza.jar" >&2
  exit 1
fi

sudo systemctl stop dipalza-app.service

# Swap atómico del symlink (sin ventana intermedia sin 'current')
ln -sfn "$RELEASE_DIR" "$TMP_LINK"
mv -Tf "$TMP_LINK" "$LIVE_LINK"

sudo systemctl start dipalza-app.service

# Health check: reintenta por ~15s a que el servicio responda
for i in $(seq 1 15); do
  if curl -sf http://localhost:8080/actuator/health > /dev/null; then
    echo "Servicio arriba y respondiendo."
    break
  fi
  if [ "$i" -eq 15 ]; then
    echo "ERROR: el servicio no respondió tras el deploy." >&2
    exit 1
  fi
  sleep 1
done

# Retiene solo las últimas 3 carpetas de release (por nombre de versión, orden de modificación)
cd "$BASE/releases"
ls -1dt */ | tail -n +4 | xargs -r rm -rf --
```

**Nota sobre el health check:** `spring-boot-starter-actuator` ya es una dependencia del proyecto (`pom.xml:110`) y no tiene configuración custom de `management.endpoints` — con la config por defecto, `/actuator/health` queda expuesto sin pasos adicionales.

### 3. Cambio en el `.service` de systemd (una sola vez, manual)

El unit file actual apunta directo a `/opt/dipalza-app/dipalza.jar`. Hay que cambiar su `ExecStart` para que apunte al symlink:

```ini
ExecStart=/usr/bin/java -jar /opt/dipalza-app/current/dipalza.jar
```

Luego `sudo systemctl daemon-reload`.

## Prerrequisitos (configuración manual única en el servidor, antes de que el pipeline funcione)

Esto se hace una sola vez, a mano, no vía GitHub Actions:

1. Crear el usuario `deploy-dipalza`, autorizar la llave pública SSH, configurar sudoers restringido a los 3 comandos `systemctl {stop,start,restart} dipalza-app.service` — ver pasos detallados ya compartidos en la conversación.
2. `chown -R deploy-dipalza:deploy-dipalza /opt/dipalza-app`.
3. Migrar el jar actualmente corriendo a `/opt/dipalza-app/releases/<version-actual>/dipalza.jar`, crear el symlink inicial `current -> releases/<version-actual>`.
4. Actualizar el `ExecStart` del `.service` para apuntar a `current/dipalza.jar` (arriba) y `daemon-reload`.
5. Copiar `scripts/deploy-remote.sh` a `/opt/dipalza-app/scripts/deploy-remote.sh` en el servidor y darle permiso de ejecución (`chmod +x`). Este script no viaja versionado por release — vive fijo en el servidor; si cambia, se actualiza a mano o en un prerrequisito posterior separado.
6. Cargar los 3 secrets en GitHub (Settings → Secrets and variables → Actions): `DEPLOY_SSH_KEY`, `DEPLOY_SSH_HOST`, `DEPLOY_SSH_USER`.

## Manejo de errores

- Si el `.jar` no llega a copiarse (SCP falla): el job de GitHub Actions falla antes de tocar el servicio — el servicio sigue corriendo la versión anterior sin interrupción.
- Si `deploy-remote.sh` falla en el health check: el script termina con `exit 1`, el job de GitHub Actions se marca como fallido. El servicio queda *arrancado* con la nueva versión (no hay rollback automático) — el usuario debe decidir manualmente si revertir el symlink a la versión anterior (que sigue disponible en `releases/`) y reiniciar el servicio a mano.
- Rollback manual: `ln -sfn releases/<version-anterior> current && sudo systemctl restart dipalza-app.service`.

## Fuera de alcance (YAGNI para esta primera versión)

- Rollback automático si el health check falla.
- Despliegue de `dipalza_web_client` (no aplica, va empaquetado en el jar).
- Zero-downtime real (blue/green, dos instancias) — hay un breve corte entre `stop` y `start`.
- Notificaciones (Slack/email) de éxito/fracaso del deploy.
