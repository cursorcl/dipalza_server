# Frontend embebido (`dipalza/src/main/resources/static/`)

`dipalza_server` sirve el frontend web (`dipalza_web_client`) embebido
dentro de su propio jar: el build compilado de Angular vive commiteado
directamente en `dipalza/src/main/resources/static/`, y Spring Boot
empaqueta automáticamente todo `src/main/resources/**` en el jar final.

**No hay ninguna automatización que sincronice ambos repos.** Actualizar
este contenido es un paso manual:

```bash
cd dipalza_web_client
npm ci
npx ng build --output-path=/tmp/web_client_build_output

cd dipalza_server
git rm -r dipalza/src/main/resources/static/
cp -r /tmp/web_client_build_output/browser/. dipalza/src/main/resources/static/
cp /tmp/web_client_build_output/3rdpartylicenses.txt dipalza/src/main/resources/static/
# prerendered-routes.json NO se copia — es metadata de build sin uso en este SPA
```

`ng build` usa por defecto la configuración `production`, que toma
`src/environments/environment.ts` (apunta a `ventas.dynalias.net:8080`) —
verificar que la URL correcta quedó embebida antes de comitear:

```bash
grep -o "ventas\.dynalias\.net[a-zA-Z0-9:/._-]*" dipalza/src/main/resources/static/*.js | sort -u
```

## Gotcha: el tipo de commit importa para el release

Este repo usa `semantic-release` con el preset Angular por defecto (ver
`release.config.js`, sin `releaseRules` custom): solo los commits `fix:`,
`feat:` o con `BREAKING CHANGE` disparan una nueva versión. Un commit
`chore:` (o `docs:`, `refactor:`, etc.) **no genera release**, aunque el
merge a `main` sea exitoso.

Como el flujo del proyecto usa **squash-merge**, el título del PR se
convierte en el mensaje del commit final en `main` — así que ese título
debe empezar con `fix:` (o `feat:` si agrega funcionalidad nueva), no
`chore:`, para que la actualización del frontend embebido efectivamente
libere una versión nueva. Ocurrió exactamente este error el 2026-08-02
(PR #16, titulado `chore: ...`): el merge fue exitoso pero
`commit-analyzer` reportó "no relevant changes, so no new version is
released" — hubo que corregirlo con un PR de seguimiento (`fix: ...`).

**Antes de actualizar este directorio:** verificar que el título del PR
uses `fix:` como mínimo, y confirmar tras el merge que el workflow
`Release` efectivamente publicó una versión nueva
(`gh run list --workflow=release.yml --limit 1`).
