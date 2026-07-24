# Despliegue continuo del backend a servidor remoto — Diseño

## Objetivo

Cuando `semantic-release` publica una nueva versión (GitHub Release) de `dipalza_server`, desplegarla automáticamente al servidor de producción del usuario, con una aprobación manual de por medio, sin downtime evitable y con posibilidad de rollback manual a una versión anterior.

## Alcance

Solo el backend (`dipalza_server`). El JAR de Spring Boot ya incluye los estáticos del frontend (Angular) empaquetados — no existe un despliegue separado para `dipalza_web_client` en este diseño.

## Arquitectura

```
GitHub Release publicada (semantic-release adjunta dipalza-<version>.jar)
        │
        ▼
Workflow "Deploy" (release: published)
        │
        ▼
Environment "production" ──► pausa, espera aprobación manual del usuario
        │  (aprobado)
        ▼
1. Descarga el .jar de la Release
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

- Trigger: `on: release: types: [published]`.
- Job `deploy` con `environment: production` (el Environment y su regla de "required reviewer" se configuran a mano en GitHub Settings → Environments, no vía código — GitHub pausa el job hasta que el usuario lo aprueba desde la pestaña Actions).
- Pasos:
  1. Descargar el asset `dipalza-*.jar` de la release que disparó el evento (`github.event.release.tag_name` / `github.event.release.assets`), vía `gh release download` con `GITHUB_TOKEN`.
  2. Configurar el agente SSH con la llave privada (`secrets.DEPLOY_SSH_KEY`).
  3. `scp` del jar descargado a `deploy-dipalza@<host>:/opt/dipalza-app/releases/<version>/dipalza.jar` (crea la carpeta remota primero con `ssh ... mkdir -p`).
  4. `ssh deploy-dipalza@<host> '/opt/dipalza-app/scripts/deploy-remote.sh <version>'` — ejecuta el script ya presente en el servidor (ver componente 2), no un script que viaje en cada corrida.
  5. Si el script falla (exit code ≠ 0), el job falla y el usuario lo ve en GitHub Actions.
- Secrets nuevos requeridos en GitHub: `DEPLOY_SSH_KEY` (privada), `DEPLOY_SSH_HOST`, `DEPLOY_SSH_USER` (o hardcodear `deploy-dipalza` si no cambia nunca).

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
6. Configurar el GitHub Environment `production` en Settings → Environments, con el usuario como required reviewer, y cargar los 3 secrets (`DEPLOY_SSH_KEY`, `DEPLOY_SSH_HOST`, `DEPLOY_SSH_USER`).

## Manejo de errores

- Si el `.jar` no llega a copiarse (SCP falla): el job de GitHub Actions falla antes de tocar el servicio — el servicio sigue corriendo la versión anterior sin interrupción.
- Si `deploy-remote.sh` falla en el health check: el script termina con `exit 1`, el job de GitHub Actions se marca como fallido. El servicio queda *arrancado* con la nueva versión (no hay rollback automático) — el usuario debe decidir manualmente si revertir el symlink a la versión anterior (que sigue disponible en `releases/`) y reiniciar el servicio a mano.
- Rollback manual: `ln -sfn releases/<version-anterior> current && sudo systemctl restart dipalza-app.service`.

## Fuera de alcance (YAGNI para esta primera versión)

- Rollback automático si el health check falla.
- Despliegue de `dipalza_web_client` (no aplica, va empaquetado en el jar).
- Zero-downtime real (blue/green, dos instancias) — hay un breve corte entre `stop` y `start`.
- Notificaciones (Slack/email) de éxito/fracaso del deploy.
