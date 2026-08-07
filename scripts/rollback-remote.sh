#!/usr/bin/env bash
set -euo pipefail

RAW_VERSION="${1:?Uso: rollback-remote.sh <version-sin-v> (ej. 1.2.3)}"
BASE="${DEPLOY_BASE:-/opt/dipalza-app}"
SERVICE="${DEPLOY_SERVICE:-dipalza-app.service}"
HEALTH_URL="${DEPLOY_HEALTH_URL:-http://localhost:8081/actuator/health}"
HEALTH_RETRIES="${DEPLOY_HEALTH_RETRIES:-15}"

# Recibe el número de versión sin el prefijo 'v' (más cómodo de teclear a
# mano en una emergencia); las carpetas de release en el servidor sí usan
# el tag completo de la GitHub Release (con 'v'), así que se antepone acá.
if ! [[ "$RAW_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$ ]]; then
  echo "ERROR: version '$RAW_VERSION' no tiene un formato válido (esperado algo como 1.2.3, sin 'v', opcionalmente con sufijo -algo)" >&2
  exit 1
fi

VERSION="v$RAW_VERSION"
RELEASE_DIR="$BASE/releases/$VERSION"
LIVE_LINK="$BASE/current"

if [ ! -f "$RELEASE_DIR/dipalza.jar" ]; then
  echo "ERROR: no existe $RELEASE_DIR/dipalza.jar (¿la versión $VERSION nunca se desplegó, o ya fue podada por la retención de últimas 3?)" >&2
  exit 1
fi

sudo systemctl stop "$SERVICE"

ln -sfn "$RELEASE_DIR" "$LIVE_LINK"

sudo systemctl start "$SERVICE"

healthy=0
for i in $(seq 1 "$HEALTH_RETRIES"); do
  if curl -sf "$HEALTH_URL" > /dev/null; then
    healthy=1
    break
  fi
  sleep 1
done

if [ "$healthy" -ne 1 ]; then
  echo "ERROR: el servicio no respondió en $HEALTH_URL tras el rollback." >&2
  exit 1
fi

echo "Rollback a la versión $VERSION completado y respondiendo en $HEALTH_URL."
