# Login `dipalza_app` en deploy_desde_cero

## Problema
El login SQL `dipalza_app` (usado en producción desde el PR #29, en vez de
`sa`) se creó a mano directo en el servidor. El paquete
`base_de_datos/deploy_desde_cero/` no lo crea, así que un deploy desde cero
nuevo queda sin este login y requiere un paso manual no documentado en el
repo.

## Alcance verificado contra el servidor real (cursorcl.dynalias.com:1777)
- **LOGIN** (`master`, nivel instancia): SQL Login, `CHECK_POLICY = ON`,
  `CHECK_EXPIRATION = OFF`.
- **USER en `[ventas]`**: miembro de `db_datareader` + `db_datawriter`,
  `CONNECT`.
- **USER en `[Mastersoft]`**: mismos roles (lo usa el datasource de
  facturación, `application-prod-sec.yml`).
- Sin permisos a nivel de objeto: la app no llama stored procedures directo
  (sin `@Procedure`/`CallableStatement`/`EXEC` en el código Java), por lo
  que `db_datareader`/`db_datawriter` es todo lo que necesita.

## Diseño
Extender `00_crear_base_datos.sql` (que ya crea `[ventas]`) agregando, al
final, la creación del LOGIN y de los dos USERs. Se elige este archivo en
vez de un script nuevo porque:
- Ambas bases (`Mastersoft` prerrequisito, `ventas` recién creada) ya
  existen en ese punto de la secuencia.
- Los roles fijos (`db_datareader`/`db_datawriter`) no dependen del esquema
  que crea `01`.
- Mantiene "crear la base" y "dar acceso a la base" en un mismo paso, sin
  sumar un décimo archivo a la secuencia 00→08 documentada en el encabezado
  del paquete.

La clave real **no** se hardcodea (mismo patrón que `DB_PASSWORD`/
`JWT_SECRET`: se configura directo en el servidor). El script usa un
placeholder (`CAMBIAR_ESTA_CLAVE`) con un comentario indicando que debe
coincidir con `DB_PASSWORD`/`FACTURACION_DB_PASSWORD` en el servidor de la
app.

Idempotencia: sigue la convención ya usada en `00` (guard con
`IF ... RAISERROR` si el login ya existe, en vez de sobreescribir en
silencio).

## Fuera de alcance
- No se cambia nada en el servidor real (el login ya existe ahí).
- No se agregan permisos a nivel de objeto (no son necesarios hoy).
- No se toca el flujo de `03`/`05`/`08` (jobs y triggers siguen corriendo
  bajo su dueño actual, no bajo `dipalza_app`).
