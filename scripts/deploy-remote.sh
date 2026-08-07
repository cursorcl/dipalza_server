#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:?Uso: deploy-remote.sh <version>}"
BASE="${DEPLOY_BASE:-/opt/dipalza-app}"
SERVICE="${DEPLOY_SERVICE:-dipalza-app.service}"
HEALTH_URL="${DEPLOY_HEALTH_URL:-http://localhost:8081/actuator/health}"
HEALTH_RETRIES="${DEPLOY_HEALTH_RETRIES:-60}"
KEEP_RELEASES=3

RELEASE_DIR="$BASE/releases/$VERSION"
LIVE_LINK="$BASE/current"

if [ ! -f "$RELEASE_DIR/dipalza.jar" ]; then
  echo "ERROR: no existe $RELEASE_DIR/dipalza.jar" >&2
  exit 1
fi

sudo systemctl stop "$SERVICE"

# El servicio ya está detenido acá, así que no hace falta un truco de
# symlink temporal + mv para atomicidad frente a tráfico en vivo. `-n`
# evita que, si 'current' ya es un symlink a un directorio, ln siga el
# enlace y cree el nuevo symlink DENTRO de él en vez de reemplazarlo.
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
  echo "ERROR: el servicio no respondió en $HEALTH_URL tras el deploy." >&2
  exit 1
fi

echo "Servicio arriba y respondiendo en $HEALTH_URL."

# Refresca el mtime de la versión recién desplegada antes de podar: si se
# redespliega una versión que ya existía (mkdir -p sobre un directorio
# existente, o sobrescritura del jar) no la sigue tanto "current" como
# la más reciente, así que sin este touch podría terminar podada aunque
# sea la que está sirviendo tráfico.
touch "$RELEASE_DIR"

cd "$BASE/releases"
ls -1dt */ | tail -n +$((KEEP_RELEASES + 1)) | xargs -r rm -rf --

echo "Deploy de la versión $VERSION completado."
