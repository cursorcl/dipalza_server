# Migración de [ventas] a PostgreSQL + sincronización Mastersoft↔ventas desde la app

**Fecha:** 2026-08-22
**Repos afectados:** `dipalza_server`
**Feature previa relacionada:** [deploy_desde_cero](2026-07-19-deploy-desde-cero-ventas-design.md), [login dipalza_app en deploy_desde_cero](2026-08-08-login-dipalza-app-deploy-desde-cero-design.md)

## Contexto y problema

El plan original era desplegar `dipalza_server` (VPS) contra una instancia SQL Server en la máquina Windows 11 del cliente final, con `[ventas]` y `[Mastersoft]` en la misma instancia — igual que hoy en desarrollo (trauco). Ese diseño depende de **SQL Server Agent** para 4 jobs recurrentes que sincronizan Mastersoft↔ventas (stock, tablas maestras, precios, lista de precio activa).

Dos hallazgos bloquean ese plan:

1. **SQL Server en el servidor del cliente es Express Edition (16.0.1000.6)**. Express es la única edición que no incluye SQL Server Agent — los 4 jobs no pueden existir ahí.
2. **La máquina Windows con la base de datos no está siempre encendida**, y está detrás de un router Arris (sin IP pública fija conocida, sin túnel configurado hoy). Esto ya afecta la disponibilidad de todo el sistema si `[ventas]` vive ahí — no es solo un problema de scheduling.

Decisión del usuario: mover `[ventas]` a **PostgreSQL, corriendo en el mismo VPS que `dipalza_server`** (coubicada con la app). `[Mastersoft]` sigue siendo SQL Server en la máquina del cliente — es su ERP existente, no se migra. La sincronización entre ambas bases, que hoy depende de que estén en la misma instancia (triggers con `UPDATE [ventas]...` cross-database, stored procedures con `MERGE` cross-database), se rediseña para vivir **dentro de la app** (`dipalza_server`, Spring Boot), como reemplazo directo de los 4 jobs de Agent.

## Alcance

- Nueva base `[ventas]` en PostgreSQL, en el VPS, con el mismo esquema funcional que hoy (`01_esquema_ventas.sql`/`02_listaprecioactiva_fuente.sql` traducidos a DDL Postgres).
- Nuevo componente `MastersoftSyncScheduler` en `dipalza_server`: 4 tareas `@Scheduled`, mismos intervalos que los jobs actuales (Stock 15s, Price 15s, ListaPrecioActiva 30s, MasterData 1min), que reemplazan `usp_ProcessStockUpdateQueue`, `usp_ProcessMasterDataQueue`, `usp_ProcessPriceUpdateQueue` (hoy en `ventas`) y `usp_ProcessListaPrecioActivaQueue` (hoy en Mastersoft) con lógica Java equivalente.
- **Todos los ambientes migran a Postgres**, no solo producción: trauco (desarrollo) se migra primero, con sus datos reales existentes, como validación antes de tocar el servidor del cliente final.
- Producción del cliente final parte con `[ventas]` vacía (nunca existió ahí, bloqueada hasta ahora por el problema de Agent) — se puebla directo desde Mastersoft, sin migración de datos.
- 6 de los 7 triggers actuales en Mastersoft **no cambian** (solo escriben colas dentro de la propia base Mastersoft). Se elimina únicamente `trg_listaprecioactiva_resync`, el único que hacía una escritura cross-database hacia `[ventas]`; su lógica (poner `PrecioLista2 = 0` si no queda lista secundaria activa) se mueve a `ListaPrecioActivaSyncService`.
- Se elimina la creación de los 4 jobs de SQL Server Agent del paquete `deploy_desde_cero` (`05_jobs_msdb.sql`/`08_habilitar_jobs.sql`) — ya no aplican, ni siquiera en un servidor que sí tuviera Agent, porque `[ventas]` ya no está en esa instancia.

**Fuera de alcance (temas separados, ya identificados pero no parte de este spec):**
- Conectividad VPS↔máquina Windows del cliente (VPN, DDNS, firewall, habilitar TCP/IP en SQL Server Express). Es un **prerrequisito duro** para que cualquiera de los 4 servicios de sync funcione contra el Mastersoft real, pero se resuelve como su propio proyecto.
- Migración de `[Mastersoft]` — se queda donde está, sin cambios de motor.
- Alta disponibilidad de la máquina del cliente (que no esté siempre encendida) — aceptado como limitación conocida; el diseño de sync es resiliente a que Mastersoft no responda (reintenta en el próximo ciclo), pero no la resuelve.

## Arquitectura

```
┌─────────────────────────┐         ┌──────────────────────────────┐
│   VPS                    │         │  Windows 11 (cliente, tras    │
│                           │         │  router Arris — conectividad  │
│  dipalza_server (Spring)  │◄───────►│  fuera de este spec)          │
│   ├─ JPA → ventas         │  JDBC   │                               │
│   │   (PostgreSQL, local) │  (red   │  SQL Server Express            │
│   └─ facturacionJdbcTpl   │  poco   │   └─ [Mastersoft]              │
│       → Mastersoft ───────┼─fiable─►│       (triggers locales,       │
│         (ya existe hoy,   │         │        sin Agent)              │
│         reusado)          │         │                                │
└─────────────────────────┘         └──────────────────────────────┘
```

`MastersoftSyncScheduler` corre dentro de `dipalza_server`, reutilizando el `facturacionJdbcTemplate`/`facturacionDataSource` que ya existe (`FacturacionDbConfig`, usado hoy por `FacturacionService`) para leer/escribir en Mastersoft, y los repositorios JPA normales (ahora contra Postgres) para `ventas`.

## Componentes

### `StockSyncService`
Reclama lote de `Mastersoft.StockUpdateQueue` (mismo patrón `UPDATE ... OUTPUT` en T-SQL vía `facturacionJdbcTemplate`, Mastersoft sigue siendo SQL Server), agrega delta por `Articulo`, hace upsert en `ventas.producto` (Postgres: `INSERT ... ON CONFLICT (articulo) DO UPDATE`, equivalente al `MERGE` actual incluyendo el `LEFT JOIN` a `ARTICULO`/`articulosnumerados` de Mastersoft para poblar filas nuevas), borra las filas reclamadas en Mastersoft.

### `MasterDataSyncService`
Reclama de `Mastersoft.MasterDataUpdateQueue`, filtra eventos de `msosttablas`, resuelve el valor actual por `(tabla, codigo)`, aplica upsert/delete en Postgres a `ruta` (tabla `017`), `condicionventa` (`009`), `conduccion` (`015`), `ila` (`004`) — mismo mapeo de códigos que hoy.

### `PriceSyncService`
Reclama de `Mastersoft.PriceUpdateQueue`, resuelve el precio activo por `(Articulo, Rol)` contra `Mastersoft.ListaPrecioActiva` + `Mastersoft.PRECIOS`, actualiza `VentaNeto`/`PrecioLista2` en `ventas.producto` (Postgres) según el rol resuelto.

### `ListaPrecioActivaSyncService`
Dirección inversa: reclama de `ventas.ListaPrecioActivaQueue` (ahora en Postgres), aplica el guard en Java (**debe existir siempre un rol `'P'`** antes de escribir — reemplaza `trg_listaprecioactiva_guard`, que en Mastersoft solo protege escrituras directas ahí, no las que vienen de la app), hace upsert del mirror en `Mastersoft.ListaPrecioActiva`, y si tras la operación no queda rol `'S'` activo, pone `PrecioLista2 = 0` directo en `ventas.producto` (Postgres) — reemplaza la parte de `trg_listaprecioactiva_resync` que cruzaba a `[ventas]`.

## Migración de esquema y datos

**Trauco (validación, con datos reales existentes):**
1. Nueva instancia PostgreSQL en trauco (contenedor Docker separado, puerto propio).
2. Traducir `01_esquema_ventas.sql`/`02_listaprecioactiva_fuente.sql` a DDL Postgres (tipos, `IDENTITY`→`GENERATED ALWAYS AS IDENTITY`, `COLLATE Modern_Spanish_CI_AS`→locale/collation Postgres equivalente).
3. Migrar los datos reales de `[ventas]` (SQL Server, trauco) a Postgres con `pgloader` (soporta SQL Server→Postgres directo, traduce tipos automáticamente) — validar conteos por tabla contra el origen antes de dar por buena la migración.
4. Auditar toda `@Query(nativeQuery = true)` en los repositorios JPA existentes y portar cualquier sintaxis T-SQL-específica a Postgres (ya se identificó al menos `HistorialPosicionRepository.resumenPorDia`, cuyo `CAST(... AS date)` es compatible sin cambios; falta revisar el resto de repositorios).
5. Cambiar dialecto Hibernate (`org.hibernate.dialect.SQLServerDialect` → `org.hibernate.dialect.PostgreSQLDialect`; quitar `database-platform: ...spatial.dialect.sqlserver...`, no hay columnas espaciales en el modelo).
6. Nuevo perfil/config Spring con datasource Postgres (driver `org.postgresql.Driver`, url `jdbc:postgresql://...`).
7. Desplegar `MastersoftSyncScheduler` contra el Mastersoft de desarrollo (mismo trauco) y validar paridad de comportamiento contra los jobs T-SQL actuales antes de deshabilitarlos ahí.

**Producción del cliente final (sin datos previos):**
1. Esquema Postgres limpio (mismo DDL validado en trauco).
2. Poblado inicial equivalente al paso `07` actual — corrido una sola vez, leyendo directo de Mastersoft (vía `facturacionJdbcTemplate` o `pgloader` apuntando Mastersoft→Postgres para la carga masiva puntual).
3. Bloqueado hasta que la conectividad VPS↔Windows esté resuelta (fuera de este spec).

## Testing

TDD por servicio (uno por uno: `StockSyncService`, `MasterDataSyncService`, `PriceSyncService`, `ListaPrecioActivaSyncService`), mockeando `facturacionJdbcTemplate` y los repositorios Postgres — mismo rigor usado en el resto de esta sesión (ver `PosicionServiceTest`, `HistorialPanelComponent`, etc.). Casos a cubrir en cada uno:
- Reclamo de lote correcto (no reclama filas ya procesadas, no reclama más del batch size).
- Idempotencia: si falla a mitad de camino, el reintento no duplica ni pierde eventos.
- Comportamiento sin eventos pendientes (no-op limpio).
- Casos de negocio específicos de cada procesador (ej. producto nuevo vs. existente en Stock; guard de lista principal en ListaPrecioActiva).

## Manejo de errores

Cada tarea `@Scheduled` con `try/catch` que loguea sin propagar (un ciclo fallido no debe matar el scheduler ni afectar a los otros 3) — mismo patrón que `PosicionService.registrarUbicacion` con `DeteccionParadaService`. Dado que el enlace de red hacia Mastersoft va a ser inherentemente inestable (VPN + máquina no siempre encendida), se agrega métrica/log de "última sincronización exitosa" por servicio, para poder detectar si Mastersoft lleva mucho tiempo sin responder.

## Estimación

| Bloque | Estimado |
|---|---|
| DDL Postgres (`01`/`02` traducidos) | 0.5–1 día |
| Migración de datos reales de trauco (`pgloader` + validación) | 0.5–1 día |
| Auditoría y port de `nativeQuery` T-SQL existentes | 0.5 día |
| Dialecto Hibernate + config Spring (nuevo perfil, driver Postgres) | 0.25–0.5 día |
| 4 servicios de sync + scheduler (TDD) | 4–6 días |
| Manejo de errores/idempotencia + pruebas bajo red inestable | 0.5–1 día |
| Validación end-to-end en trauco | 1 día |
| Rollout en producción (una vez resuelta la conectividad) | 0.5–1 día |
| **Total** | **~8–12 días de trabajo enfocado** (2–3 semanas de calendario con revisión/iteración) |

Mayor incertidumbre: los 4 servicios de sync (toda la lógica de negocio) y la migración de datos con `pgloader` (depende de la limpieza de los datos reales de trauco).
