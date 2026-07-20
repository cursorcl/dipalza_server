# Deploy desde cero de la base de datos `ventas` — Diseño

## 1. Contexto y objetivo

Hoy solo existe la base `Mastersoft` (ERP). La base `ventas` (app Dipalza) no existe en el ambiente objetivo. Se necesita un conjunto de scripts SQL Server ejecutables en orden, de punta a punta, que:

1. Cree la base de datos `ventas` vacía.
2. Cree su esquema completo (sin datos de negocio).
3. Instale las colas (outbox), triggers, procedimientos y jobs que mantienen `ventas` y `Mastersoft` sincronizadas de forma asíncrona.
4. Pueble `ventas` por primera vez con datos maestros existentes en `Mastersoft` (rutas, condición de venta, conducción, ila, productos, clientes, vendedores).

La lógica de sincronización incremental (colas + triggers + procesadores + jobs) **ya existe y está probada** en `db/install_dipalza_sync.sql` (1189 líneas, fuente única de verdad según `db/diseno_sincronizacion_dipalza.md` sección 6). Este diseño no reimplementa esa lógica: la extrae en archivos separados y le agrega dos piezas nuevas que no existían — creación de la base de datos y poblado inicial.

## 2. Alcance

**Incluye:**
- Partir `install_dipalza_sync.sql` en archivos numerados por responsabilidad, ejecutables en secuencia.
- Nuevo script de creación de la base de datos `ventas`.
- Nuevo script de poblado inicial masivo desde `Mastersoft` hacia `ventas`.

**No incluye (fuera de alcance, explícitamente descartado durante el diseño):**
- Poblar `vendedor_ruta` (el usuario confirmó que no debe poblarse en la carga inicial).
- Configurar `ListaPrecioActiva` (queda como paso manual documentado, igual que en el diseño original sección 6 "Configuración inicial obligatoria").
- Calcular el stock inicial real vía `calcularStock`/`calcularStockNumerado` (procedimientos que existen en Mastersoft) — se deja en `0` con un TODO explícito; el usuario implementará esa parte después.
- Determinar el origen de `producto.costo` — se deja en `0` con TODO.
- Modificar `install_dipalza_sync.sql` original (queda intacto como referencia histórica, tal como indica su propia documentación).
- No es idempotente: asume que todas las tablas destino están vacías, igual que el script del que se deriva.

## 3. Estructura de archivos

Carpeta nueva: `base_de_datos/deploy_desde_cero/`.

| # | Archivo | Contenido | Base | Origen |
|---|---|---|---|---|
| 00 | `00_crear_base_datos.sql` | `CREATE DATABASE ventas` con collation `Modern_Spanish_CI_AS` | master | Nuevo |
| 01 | `01_esquema_ventas.sql` | Todas las `CREATE TABLE` de la app (`install_dipalza_sync.sql:62-277`), índices (`:278-297`), seed de roles (`:299`) + columna `producto.costo` (money NOT NULL DEFAULT 0) agregada — ver nota de drift abajo | ventas | Extraído + corrección |
| 02 | `02_listaprecioactiva_fuente.sql` | `ListaPrecioActiva`/`ListaPrecioActivaQueue` fuente (`:303-322`) + triggers outbox/guard (`:323-359`) + `tgr_ventadetalle_producto` (`:360-400`) | ventas | Extraído |
| 03 | `03_colas_triggers_mastersoft.sql` | 3 colas (`:404-448`), `ListaPrecioActiva` réplica, todos los triggers de Mastersoft (stock `:449-552`, tablas maestras `:553-586`, precios `:587-625`, resync `:626-659`, guard `:660-676`), procesador inverso `usp_ProcessListaPrecioActivaQueue` (`:677-740`) | Mastersoft | Extraído |
| 04 | `04_procesadores_ventas.sql` | 3 procesadores: stock (`:743-816`), tablas maestras (`:817-929`), precios (`:931-1014`) | ventas | Extraído |
| 05 | `05_jobs_msdb.sql` | 4 jobs del Agent (`:1017-1148`), creados con **`@enabled = 0`** (ver nota de corrección post-revisión abajo) | msdb | Extraído + corrección |
| 06 | `06_configuracion_inicial.sql` | `INSERT` de `ListaPrecioActiva` — **comentado**, con placeholder `<codigo>` para que el usuario lo complete manualmente (igual que sección 6 del diseño original) | ventas | Extraído (sin cambios) |
| 07 | `07_poblado_inicial_ventas.sql` | Carga masiva desde Mastersoft — **nuevo**, ver sección 4 | ventas ↔ Mastersoft | Nuevo |
| 08 | `08_habilitar_jobs.sql` | Habilita los 4 jobs de `05` vía `sp_update_job` — **nuevo**, ejecutar solo después de que `07` termine con éxito y el seed manual de `06` esté aplicado (ver sección 6) | msdb | Nuevo |

Cada archivo mantiene su propio `USE <base>` y separadores `GO`, para poder ejecutarse independientemente si hace falta re-correr un paso puntual. El orden de ejecución es estrictamente 00→08.

## 4. Poblado inicial (`07_poblado_inicial_ventas.sql`)

Todo dentro de una transacción con `SET XACT_ABORT ON` (mismo patrón de seguridad que los procesadores existentes). Pensado para correr **una sola vez** sobre tablas vacías.

| Tabla destino | Origen Mastersoft | Mapeo |
|---|---|---|
| `ruta` | `msosttablas` (tabla='017') | `codigo`, `descripcion` directos; `conduccion_id` calculado: `'001'→'9999'`, `'003'→'9998'`, cualquier otro→`'9997'` (regla de negocio confirmada por el usuario) |
| `condicionventa` | `msosttablas` (tabla='009') | `codigo`, `descripcion`, `dias`=`valor` — igual que `usp_ProcessMasterDataQueue` |
| `conduccion` | `msosttablas` (tabla='015') | `codigo`, `descripcion`, `valor` — igual que el procesador existente |
| `ila` | `msosttablas` (tabla='004') | `codigo`, `descripcion`, `valor` — igual que el procesador existente |
| `producto` | `ARTICULO` + `articulosnumerados` (flag) + `PRECIOS`/`ListaPrecioActiva` (si hay lista secundaria activa) | `Descripcion`, `VentaNeto`, `PorcIla`, `PorcCarne`, `Unidad`, `CodigoIla` directos; `numbered` desde `articulosnumerados`; `Stock=0` (TODO: reemplazar por `calcularStock`/`calcularStockNumerado` — pendiente, el usuario lo implementará después); `costo=0` (TODO: origen no identificado); `stockVentas=0`, `piezasVentas=0` |
| `cliente` | `msoclientes` | Mapeo por nombre supuesto 1:1 con la columna destino (`rut`,`codigo`,`razon`,`direccion`,`ciudad`,`giro`,`telefono`,`codigo_ruta`,`codigo_vendedor`) — **cada columna marcada con comentario `-- TODO: verificar nombre real de columna en msoclientes`**, ya que no se dispone del DDL exacto de esa tabla en este repo |
| `vendedor` | `msovendedor` | Mismo tratamiento con TODO (`rut`,`codigo`,`tipo`,`nombre`,`ciudad`,`comuna`,`direccion`,`telefono`) |
| `vendedor_ruta` | — | **No se puebla** (confirmado) |

Notas de implementación:
- El orden de inserción respeta las FKs existentes: `ruta`/`condicionventa`/`conduccion`/`ila` antes que `producto`/`cliente`/`vendedor` (que no dependen de ellas para el insert, pero `cliente.codigo_ruta` referencia lógicamente `ruta` aunque no hay FK declarada en el esquema).
- Los `TODO` quedan como comentarios `-- TODO: ...` inmediatamente sobre la línea afectada, no como bloques separados, para que sean fáciles de ubicar y editar antes de ejecutar en un ambiente real.
- Todas las comparaciones cross-collation usan `COLLATE Modern_Spanish_CI_AS`, igual que el resto de `install_dipalza_sync.sql`.

### 4.1 Drift de esquema detectado: `producto.costo`

La entidad JPA `Producto` (`dipalza_server/dipalza/src/main/java/cl/eos/dipalza/entity/Producto.java`) tiene un campo `costo` **NOT NULL** que no existe en el `CREATE TABLE producto` de `install_dipalza_sync.sql`. El usuario confirmó que en producción esta columna **ya existe** (fue agregada fuera de este script, drift no documentado). Por lo tanto `01_esquema_ventas.sql` agrega `costo money NOT NULL DEFAULT 0` al `CREATE TABLE producto`, y `07_poblado_inicial_ventas.sql` la puebla en `0` (mismo TODO ya descrito).

## 5. Verificación

Después de armar los 9 archivos:
- Revisión visual línea por línea contra `install_dipalza_sync.sql` para confirmar que la extracción no perdió ni alteró nada (diff conceptual, ya que se reparte en varios archivos).
- `install_dipalza_sync.sql` permanece intacto en `db/` como referencia; no se borra ni se modifica.
- No hay entorno SQL Server disponible para ejecutar y validar en vivo durante esta sesión — la verificación es por inspección, no por ejecución. Se deja constancia explícita de esta limitación.

## 6. Riesgos / limitaciones conocidas

- Los nombres de columna de `msoclientes`/`msovendedor` son un supuesto razonable (mismo nombre que la columna destino), no un hecho verificado — **debe confirmarse antes de ejecutar en un ambiente real**.
- `producto.Stock` y `producto.costo` quedan en `0`, lo que significa que hasta que se complete el TODO correspondiente, la app mostrará stock y costo incorrectos para todo el catálogo recién cargado (mitigado porque las colas de stock sí funcionan hacia adelante — solo el valor inicial queda pendiente).
- El script de poblado no es idempotente: correrlo dos veces duplicará filas o fallará por PK duplicada.
- **Estado (2026-07-19):** los 9 scripts fueron generados y verificados estructuralmente (conteo de objetos, balance de transacciones), pero **nunca ejecutados contra una instancia SQL Server real** — no había una disponible durante esta sesión. Antes de usarlos en un ambiente real: (1) confirmar columnas reales de `msoclientes`/`msovendedor`, (2) decidir e implementar el cálculo real de `Stock` inicial vía `calcularStock`/`calcularStockNumerado`, (3) definir el origen real de `producto.costo`, (4) ejecutar en un ambiente de prueba antes de producción.
- **Condición de carrera entre `05` (jobs) y `07` (poblado inicial) — encontrada en revisión final de código, resuelta:** con el diseño original, `05_jobs_msdb.sql` creaba los 4 jobs ya habilitados (`@enabled = 1`), y `07_poblado_inicial_ventas.sql` corría después dentro de una única transacción. Si `Mastersoft` estaba recibiendo escrituras durante la ventana del deploy, los triggers de `03` encolaban eventos y los procesadores de `04` (ya habilitados por los jobs) podían hacer `MERGE` sobre `dbo.producto`/`dbo.ruta`/etc. concurrentemente con los `INSERT` de `07` — riesgo de violación de PK que aborta todo el poblado inicial, o deadlock. **Resolución (confirmada por el usuario):** los jobs se crean deshabilitados en `05` (`@enabled = 0`) y se agregó `08_habilitar_jobs.sql`, que los habilita explícitamente solo después de que `07` haya terminado con éxito y el seed manual de `ListaPrecioActiva` de `06` esté aplicado. El paquete pasa de 8 a 9 archivos; el orden de ejecución es ahora estrictamente 00→08.
