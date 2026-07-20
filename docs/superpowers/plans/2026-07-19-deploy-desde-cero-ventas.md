# Deploy desde cero de `ventas` — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Producir un set de 8 scripts SQL Server numerados, ejecutables en orden desde una instalación en blanco (solo existe `Mastersoft`), que crean la base `ventas`, su esquema, la sincronización bidireccional con `Mastersoft`, y el poblado inicial de datos maestros.

**Architecture:** `db/install_dipalza_sync.sql` (fuente de verdad existente, 1189 líneas) se extrae por rangos de línea exactos hacia archivos separados en `base_de_datos/deploy_desde_cero/`, sin modificar su contenido salvo dos correcciones puntuales (columna `producto.costo` agregada, ver spec sección 4.1). Se agregan dos archivos nuevos: creación de base de datos (00) y poblado inicial masivo (07).

**Tech Stack:** T-SQL (SQL Server), `sed` para extracción determinística de rangos de línea, `diff`/`grep` para verificación estructural (no hay instancia SQL Server disponible para ejecutar en esta sesión).

## Global Constraints

- No modificar `base_de_datos/db/install_dipalza_sync.sql` (queda intacto como referencia histórica).
- Todos los archivos nuevos usan `COLLATE Modern_Spanish_CI_AS` en toda comparación cross-collation, igual que el original.
- El poblado inicial (`07`) no es idempotente: asume tablas destino vacías.
- No poblar `vendedor_ruta` (confirmado fuera de alcance).
- `ListaPrecioActiva` no se puebla automáticamente (queda como paso manual documentado en `06`).
- `producto.Stock` y `producto.costo` se cargan en `0` con comentarios `-- TODO` explícitos.
- Columnas mapeadas desde `msoclientes`/`msovendedor` llevan comentario `-- TODO: verificar nombre real de columna` porque el DDL exacto de esas tablas no está disponible en este repo.
- No hay entorno SQL Server disponible para ejecutar los scripts en esta sesión: toda verificación es estructural (conteo de objetos vía `grep`, diffs de rango, balance de `GO`/`BEGIN`/`END`), no ejecución real. Esto debe quedar explícito en el commit final y no debe reportarse como "probado en vivo".

---

## Task 1: Estructura de carpetas + script de creación de base de datos

**Files:**
- Create: `base_de_datos/deploy_desde_cero/00_crear_base_datos.sql`

**Interfaces:**
- Produces: la base de datos `ventas` (nueva), lista para que Task 2 la use con `USE ventas;`.

- [ ] **Step 1: Crear la carpeta**

```bash
mkdir -p /Users/cursor/Dev/dipalza/application_v2.0/dipalza_server/base_de_datos/deploy_desde_cero
```

- [ ] **Step 2: Escribir `00_crear_base_datos.sql`**

```sql
/* ============================================================================
   DEPLOY DESDE CERO — Sincronización Mastersoft <-> ventas (Dipalza)
   ----------------------------------------------------------------------------
   Paquete de 8 scripts ejecutables EN ORDEN sobre una instancia SQL Server
   donde ya existe [Mastersoft] pero [ventas] todavía no existe.

   TOPOLOGÍA
     [Mastersoft] = base del ERP. Ya contiene las tablas operacionales/maestras
                    (invdetallepartes, detalledocumento, encabezadocumento,
                     msosttablas, ARTICULO, articulosnumerados, PRECIOS,
                     msoclientes, msovendedor, ...). Aquí se crean las COLAS,
                    la config de listas y los TRIGGERS.
     [ventas]     = base de la app Dipalza (la crea este mismo script 00).
                    Aquí se crea TODO el esquema de la app y los
                    PROCEDIMIENTOS que consumen las colas.
     [msdb]       = SQL Server Agent. Aquí se crean los JOBS.

   CRUCES DE BASE: todas las bases están en la misma instancia -> las
   transacciones cross-database son locales, no requieren MSDTC.

   PRERREQUISITOS
     - [Mastersoft] ya existe, con sus tablas ERP y SQL Server Agent activo.
     - [ventas] NO debe existir todavía (este script la crea).

   ORDEN DE EJECUCIÓN (archivos de esta carpeta)
     00_crear_base_datos.sql          -> crea [ventas]
     01_esquema_ventas.sql            -> esquema completo + seed de roles
     02_listaprecioactiva_fuente.sql  -> ListaPrecioActiva fuente + triggers en [ventas]
     03_colas_triggers_mastersoft.sql -> colas + triggers + procesador inverso en [Mastersoft]
     04_procesadores_ventas.sql       -> los 3 procesadores de sincronización en [ventas]
     05_jobs_msdb.sql                 -> los 4 jobs del Agent en [msdb]
     06_configuracion_inicial.sql     -> instrucciones manuales (ListaPrecioActiva + verificación)
     07_poblado_inicial_ventas.sql    -> carga masiva inicial desde [Mastersoft]

   Extraído y adaptado de base_de_datos/db/install_dipalza_sync.sql, que
   permanece intacto como referencia histórica (ver docs/superpowers/specs/
   2026-07-19-deploy-desde-cero-ventas-design.md para el detalle completo).
   ============================================================================ */

/* ============================================================================
   00_crear_base_datos.sql
   Crea la base de datos [ventas] desde cero. Ejecutar antes que cualquier
   otro script de esta carpeta. Requiere que [Mastersoft] ya exista en la
   misma instancia (no se crea aquí).
   ============================================================================ */
USE master;
GO

IF DB_ID(N'ventas') IS NOT NULL
BEGIN
    RAISERROR (N'La base de datos [ventas] ya existe. Este script asume una instalación desde cero; abortando para no pisar una base existente.', 16, 1);
END
GO

CREATE DATABASE ventas
    COLLATE Modern_Spanish_CI_AS;
GO
```

- [ ] **Step 3: Verificar sintácticamente (sin instancia SQL Server disponible, verificación estructural)**

Run: `grep -c "^GO$" /Users/cursor/Dev/dipalza/application_v2.0/dipalza_server/base_de_datos/deploy_desde_cero/00_crear_base_datos.sql`
Expected: `3`

- [ ] **Step 4: Commit**

```bash
cd /Users/cursor/Dev/dipalza/application_v2.0/dipalza_server
git add base_de_datos/deploy_desde_cero/00_crear_base_datos.sql
git commit -m "feat(db): agrega script de creación de base de datos ventas"
```

---

## Task 2: Extraer esquema de `ventas` + agregar columna `producto.costo`

**Files:**
- Create: `base_de_datos/deploy_desde_cero/01_esquema_ventas.sql`
- Reference (read-only): `base_de_datos/db/install_dipalza_sync.sql:55-300`

**Interfaces:**
- Consumes: base de datos `ventas` creada por Task 1.
- Produces: todas las tablas de la app (incluida `dbo.producto` con la columna nueva `costo`), índices, y seed de `app_role`. Task 3-8 dependen de este esquema existiendo.

- [ ] **Step 1: Extraer el rango exacto con `sed`**

```bash
cd /Users/cursor/Dev/dipalza/application_v2.0/dipalza_server
sed -n '55,300p' base_de_datos/db/install_dipalza_sync.sql > base_de_datos/deploy_desde_cero/01_esquema_ventas.sql
```

- [ ] **Step 2: Verificar que la extracción trajo las 19 tablas esperadas**

Run: `grep -c "^CREATE TABLE" base_de_datos/deploy_desde_cero/01_esquema_ventas.sql`
Expected: `19`

- [ ] **Step 3: Agregar la columna `costo` a `producto` (drift de producción documentado en la spec sección 4.1)**

Abrir `base_de_datos/deploy_desde_cero/01_esquema_ventas.sql` y ubicar el bloque `CREATE TABLE dbo.producto` (debería estar cerca de la línea 84 del archivo extraído). Cambiar:

```sql
CREATE TABLE dbo.producto (
    Articulo     varchar(255)  COLLATE Modern_Spanish_CI_AS NOT NULL,
    Descripcion  nvarchar(200) COLLATE Modern_Spanish_CI_AS NOT NULL,
    VentaNeto    money NOT NULL,
    PorcIla      decimal(5,2) NOT NULL,
    PorcCarne    decimal(5,2) NOT NULL,
    Unidad       varchar(10) COLLATE Modern_Spanish_CI_AS NOT NULL,
    Stock        money NULL,
    CodigoIla    varchar(20) COLLATE Modern_Spanish_CI_AS NULL,
    last_update  date NOT NULL DEFAULT CONVERT(date, SYSUTCDATETIME()),
    rv           timestamp NOT NULL,
    numbered     bit DEFAULT 0 NULL,
    pieces       decimal(5,2) DEFAULT 0 NULL,
    stockVentas  money DEFAULT 0 NULL,
    piezasVentas decimal(19,2) DEFAULT 0 NULL,
    PrecioLista2 money NULL,                       -- segundo precio (rol 'S')
    CONSTRAINT PK_Producto PRIMARY KEY (Articulo)
);
```

por (agrega solo la línea `costo`, comentario incluido para dejar constancia del porqué):

```sql
CREATE TABLE dbo.producto (
    Articulo     varchar(255)  COLLATE Modern_Spanish_CI_AS NOT NULL,
    Descripcion  nvarchar(200) COLLATE Modern_Spanish_CI_AS NOT NULL,
    VentaNeto    money NOT NULL,
    PorcIla      decimal(5,2) NOT NULL,
    PorcCarne    decimal(5,2) NOT NULL,
    Unidad       varchar(10) COLLATE Modern_Spanish_CI_AS NOT NULL,
    Stock        money NULL,
    CodigoIla    varchar(20) COLLATE Modern_Spanish_CI_AS NULL,
    last_update  date NOT NULL DEFAULT CONVERT(date, SYSUTCDATETIME()),
    rv           timestamp NOT NULL,
    numbered     bit DEFAULT 0 NULL,
    pieces       decimal(5,2) DEFAULT 0 NULL,
    stockVentas  money DEFAULT 0 NULL,
    piezasVentas decimal(19,2) DEFAULT 0 NULL,
    PrecioLista2 money NULL,                       -- segundo precio (rol 'S')
    costo        money NOT NULL DEFAULT 0,          -- [DRIFT] existe en producción pero no estaba en install_dipalza_sync.sql; requerido por la entidad JPA Producto
    CONSTRAINT PK_Producto PRIMARY KEY (Articulo)
);
```

- [ ] **Step 4: Verificar que la columna quedó agregada**

Run: `grep -n "costo\s*money NOT NULL DEFAULT 0" base_de_datos/deploy_desde_cero/01_esquema_ventas.sql`
Expected: una línea con `costo        money NOT NULL DEFAULT 0,` seguida del comentario `-- [DRIFT] ...`

- [ ] **Step 5: Verificar que el seed de roles quedó incluido**

Run: `grep -n "INSERT INTO dbo.app_role" base_de_datos/deploy_desde_cero/01_esquema_ventas.sql`
Expected: `INSERT INTO dbo.app_role (name) VALUES ('ROLE_ADMIN'), ('ROLE_VENDEDOR');`

- [ ] **Step 6: Commit**

```bash
git add base_de_datos/deploy_desde_cero/01_esquema_ventas.sql
git commit -m "feat(db): extrae esquema de ventas y agrega columna producto.costo"
```

---

## Task 3: Extraer `ListaPrecioActiva` fuente + triggers de `ventas`

**Files:**
- Create: `base_de_datos/deploy_desde_cero/02_listaprecioactiva_fuente.sql`
- Reference (read-only): `base_de_datos/db/install_dipalza_sync.sql:302-394`

**Interfaces:**
- Consumes: esquema de `ventas` de Task 2 (en particular `dbo.producto`, referenciada por `tgr_ventadetalle_producto`).
- Produces: `dbo.ListaPrecioActiva`, `dbo.ListaPrecioActivaQueue`, y los triggers `trg_listaprecioactiva_outbox`, `trg_listaprecioactiva_guard`, `tgr_ventadetalle_producto` en `ventas`. Task 4 (Mastersoft) reutiliza el mismo patrón de nombres de trigger (`trg_listaprecioactiva_guard`, `trg_listaprecioactiva_resync`) pero en la base opuesta — no son el mismo objeto, viven en bases distintas.

- [ ] **Step 1: Extraer el rango exacto con `sed`, con `USE ventas;` explícito al inicio**

```bash
cd /Users/cursor/Dev/dipalza/application_v2.0/dipalza_server
{
  echo "USE ventas;"
  echo "GO"
  echo ""
  sed -n '302,394p' base_de_datos/db/install_dipalza_sync.sql
} > base_de_datos/deploy_desde_cero/02_listaprecioactiva_fuente.sql
```

- [ ] **Step 2: Verificar objetos esperados**

Run: `grep -c "^CREATE TABLE" base_de_datos/deploy_desde_cero/02_listaprecioactiva_fuente.sql`
Expected: `2`

Run: `grep -c "CREATE OR ALTER TRIGGER" base_de_datos/deploy_desde_cero/02_listaprecioactiva_fuente.sql`
Expected: `3`

- [ ] **Step 3: Commit**

```bash
git add base_de_datos/deploy_desde_cero/02_listaprecioactiva_fuente.sql
git commit -m "feat(db): extrae ListaPrecioActiva fuente y triggers de ventas"
```

---

## Task 4: Extraer colas, config y triggers de `Mastersoft`

**Files:**
- Create: `base_de_datos/deploy_desde_cero/03_colas_triggers_mastersoft.sql`
- Reference (read-only): `base_de_datos/db/install_dipalza_sync.sql:397-734`

**Interfaces:**
- Consumes: nada de los scripts anteriores (Mastersoft ya tiene sus tablas ERP; este script solo agrega objetos nuevos: colas, réplica de `ListaPrecioActiva`, triggers, procesador inverso).
- Produces: `dbo.StockUpdateQueue`, `dbo.MasterDataUpdateQueue`, `dbo.ListaPrecioActiva` (réplica), `dbo.PriceUpdateQueue`, 7 triggers, y `usp_ProcessListaPrecioActivaQueue` en `Mastersoft`. Task 6 (jobs) referencia `usp_ProcessListaPrecioActivaQueue` por nombre exacto — no cambiarlo.

- [ ] **Step 1: Extraer el rango exacto con `sed`**

```bash
cd /Users/cursor/Dev/dipalza/application_v2.0/dipalza_server
sed -n '397,734p' base_de_datos/db/install_dipalza_sync.sql > base_de_datos/deploy_desde_cero/03_colas_triggers_mastersoft.sql
```

- [ ] **Step 2: Verificar objetos esperados**

Run: `grep -c "^CREATE TABLE" base_de_datos/deploy_desde_cero/03_colas_triggers_mastersoft.sql`
Expected: `4`

Run: `grep -c "CREATE OR ALTER TRIGGER" base_de_datos/deploy_desde_cero/03_colas_triggers_mastersoft.sql`
Expected: `7`

Run: `grep -c "CREATE OR ALTER PROCEDURE" base_de_datos/deploy_desde_cero/03_colas_triggers_mastersoft.sql`
Expected: `1`

Run: `head -n 2 base_de_datos/deploy_desde_cero/03_colas_triggers_mastersoft.sql`
Expected: primera línea es un comentario de bloque `/* ...`, y el archivo debe contener `USE Mastersoft;` cerca del inicio (verificar con `grep -n "USE Mastersoft;" base_de_datos/deploy_desde_cero/03_colas_triggers_mastersoft.sql`, debe existir).

- [ ] **Step 3: Commit**

```bash
git add base_de_datos/deploy_desde_cero/03_colas_triggers_mastersoft.sql
git commit -m "feat(db): extrae colas, triggers y config de Mastersoft"
```

---

## Task 5: Extraer procedimientos de `ventas`

**Files:**
- Create: `base_de_datos/deploy_desde_cero/04_procesadores_ventas.sql`
- Reference (read-only): `base_de_datos/db/install_dipalza_sync.sql:737-1014`

**Interfaces:**
- Consumes: colas creadas en Task 4 (`Mastersoft.dbo.StockUpdateQueue`, `MasterDataUpdateQueue`, `PriceUpdateQueue`, `ListaPrecioActiva`), esquema de Task 2 (`dbo.producto`, `dbo.ruta`, `dbo.condicionventa`, `dbo.conduccion`, `dbo.ila`).
- Produces: `usp_ProcessStockUpdateQueue`, `usp_ProcessMasterDataQueue`, `usp_ProcessPriceUpdateQueue` en `ventas`. Task 6 (jobs) los invoca por estos nombres exactos.

- [ ] **Step 1: Extraer el rango exacto con `sed`**

```bash
cd /Users/cursor/Dev/dipalza/application_v2.0/dipalza_server
sed -n '737,1014p' base_de_datos/db/install_dipalza_sync.sql > base_de_datos/deploy_desde_cero/04_procesadores_ventas.sql
```

- [ ] **Step 2: Verificar los 3 procedimientos**

Run: `grep -c "CREATE OR ALTER PROCEDURE" base_de_datos/deploy_desde_cero/04_procesadores_ventas.sql`
Expected: `3`

Run: `grep -n "CREATE OR ALTER PROCEDURE" base_de_datos/deploy_desde_cero/04_procesadores_ventas.sql`
Expected: tres líneas con `usp_ProcessStockUpdateQueue`, `usp_ProcessMasterDataQueue`, `usp_ProcessPriceUpdateQueue` (en ese orden)

- [ ] **Step 3: Commit**

```bash
git add base_de_datos/deploy_desde_cero/04_procesadores_ventas.sql
git commit -m "feat(db): extrae procedimientos de sincronización de ventas"
```

---

## Task 6: Extraer jobs de `msdb`

**Files:**
- Create: `base_de_datos/deploy_desde_cero/05_jobs_msdb.sql`
- Reference (read-only): `base_de_datos/db/install_dipalza_sync.sql:1017-1136`

**Interfaces:**
- Consumes: procedimientos de Task 5 y Task 4 (los `@command` de cada job hacen `EXEC dbo.usp_...`).
- Produces: 4 jobs del SQL Server Agent (`Dipalza - Procesar StockUpdateQueue`, `MasterDataUpdateQueue`, `PriceUpdateQueue`, `ListaPrecioActivaQueue`).

- [ ] **Step 1: Extraer el rango exacto con `sed`**

```bash
cd /Users/cursor/Dev/dipalza/application_v2.0/dipalza_server
sed -n '1017,1136p' base_de_datos/db/install_dipalza_sync.sql > base_de_datos/deploy_desde_cero/05_jobs_msdb.sql
```

- [ ] **Step 2: Verificar los 4 jobs**

Run: `grep -c "EXEC sp_add_job" base_de_datos/deploy_desde_cero/05_jobs_msdb.sql`
Expected: `4`

- [ ] **Step 3: Commit**

```bash
git add base_de_datos/deploy_desde_cero/05_jobs_msdb.sql
git commit -m "feat(db): extrae jobs del SQL Server Agent"
```

---

## Task 7: Extraer configuración inicial y verificación (documentación manual)

**Files:**
- Create: `base_de_datos/deploy_desde_cero/06_configuracion_inicial.sql`
- Reference (read-only): `base_de_datos/db/install_dipalza_sync.sql:1139-1189`

**Interfaces:**
- Produces: ninguna ejecución automática (todo el contenido queda comentado, tal como en el original — es instrucción para que el usuario configure `ListaPrecioActiva` manualmente después de correr 00-07).

- [ ] **Step 1: Extraer el rango exacto con `sed`**

```bash
cd /Users/cursor/Dev/dipalza/application_v2.0/dipalza_server
sed -n '1139,1189p' base_de_datos/db/install_dipalza_sync.sql > base_de_datos/deploy_desde_cero/06_configuracion_inicial.sql
```

- [ ] **Step 2: Verificar que el bloque de comentario quedó completo**

Run: `head -c 2 base_de_datos/deploy_desde_cero/06_configuracion_inicial.sql`
Expected: `/*`

Run: `tail -c 3 base_de_datos/deploy_desde_cero/06_configuracion_inicial.sql`
Expected: `*/` (posiblemente con salto de línea final)

Run: `grep -c "INSERT INTO dbo.ListaPrecioActiva" base_de_datos/deploy_desde_cero/06_configuracion_inicial.sql`
Expected: `2` (la línea activa comentada con `--` y la línea de ejemplo de la secundaria)

- [ ] **Step 3: Commit**

```bash
git add base_de_datos/deploy_desde_cero/06_configuracion_inicial.sql
git commit -m "feat(db): extrae configuración inicial y verificación manual"
```

---

## Task 8: Escribir el poblado inicial masivo desde Mastersoft

**Files:**
- Create: `base_de_datos/deploy_desde_cero/07_poblado_inicial_ventas.sql`

**Interfaces:**
- Consumes: esquema de `ventas` completo (Task 2, incluida `producto.costo`), tablas de `Mastersoft` (`msosttablas`, `ARTICULO`, `articulosnumerados`, `PRECIOS`, `ListaPrecioActiva`, `msoclientes`, `msovendedor`).
- Produces: filas iniciales en `ruta`, `condicionventa`, `conduccion`, `ila`, `producto`, `cliente`, `vendedor`. No produce filas en `vendedor_ruta` (confirmado fuera de alcance) ni en `ListaPrecioActiva` (queda para Task 7 / paso manual).

- [ ] **Step 1: Escribir el archivo completo**

```sql
/* ============================================================================
   07_poblado_inicial_ventas.sql
   Poblado inicial masivo de [ventas] desde [Mastersoft].
   Ejecutar UNA SOLA VEZ, después de 00-06, sobre tablas [ventas] vacías.
   NO es idempotente: correrlo dos veces duplicará filas o fallará por PK.
   ============================================================================ */
USE ventas;
GO

SET XACT_ABORT ON;
BEGIN TRY
    BEGIN TRANSACTION;

    -- ---- Rutas (Mastersoft.msosttablas, tabla='017') -----------------------
    -- conduccion_id: regla de negocio confirmada por el usuario (no viene de
    -- Mastersoft, msosttablas no trae ese dato para rutas).
    INSERT INTO dbo.ruta (codigo, descripcion, conduccion_id)
    SELECT
        codigo,
        descripcion,
        CASE codigo
            WHEN '001' THEN '9999'
            WHEN '003' THEN '9998'
            ELSE '9997'
        END AS conduccion_id
    FROM Mastersoft.dbo.msosttablas
    WHERE tabla = '017';

    -- ---- Condición de venta (Mastersoft.msosttablas, tabla='009') ----------
    INSERT INTO dbo.condicionventa (codigo, descripcion, dias)
    SELECT codigo, descripcion, valor
    FROM Mastersoft.dbo.msosttablas
    WHERE tabla = '009';

    -- ---- Conducción (Mastersoft.msosttablas, tabla='015') ------------------
    INSERT INTO dbo.conduccion (codigo, descripcion, valor)
    SELECT codigo, descripcion, valor
    FROM Mastersoft.dbo.msosttablas
    WHERE tabla = '015';

    -- ---- ILA (Mastersoft.msosttablas, tabla='004') -------------------------
    INSERT INTO dbo.ila (codigo, descripcion, valor)
    SELECT codigo, descripcion, valor
    FROM Mastersoft.dbo.msosttablas
    WHERE tabla = '004';

    -- ---- Productos (Mastersoft.ARTICULO + articulosnumerados) --------------
    -- TODO: Stock=0 es temporal. Reemplazar por el resultado real de
    --       calcularStock/calcularStockNumerado (procedimientos existentes
    --       en Mastersoft) cuando se implemente esa parte.
    -- TODO: costo=0 es temporal. Origen real aún no identificado en Mastersoft.
    INSERT INTO dbo.producto (
        Articulo, Descripcion, VentaNeto, PorcIla, PorcCarne, Unidad,
        Stock, CodigoIla, last_update, numbered, stockVentas, piezasVentas, costo
    )
    SELECT
        a.Articulo,
        a.Descripcion,
        a.VentaNeto,
        a.PorcIla,
        a.PorcCarne,
        a.Unidad,
        0 AS Stock,                                                   -- TODO: calcularStock/calcularStockNumerado
        a.CodigoIla,
        CONVERT(date, SYSUTCDATETIME()),
        CASE WHEN an.Articulo IS NOT NULL THEN 1 ELSE 0 END AS numbered,
        0 AS stockVentas,
        0 AS piezasVentas,
        0 AS costo                                                     -- TODO: origen real no identificado
    FROM Mastersoft.dbo.ARTICULO a
    LEFT JOIN Mastersoft.dbo.articulosnumerados an
        ON an.Articulo = a.Articulo COLLATE Modern_Spanish_CI_AS;

    -- ---- Precio secundario (rol 'S'), solo si Mastersoft.ListaPrecioActiva
    --      ya tiene una lista secundaria activa configurada -----------------
    UPDATE p
       SET p.PrecioLista2 = pr.VentaNeto
    FROM dbo.producto p
    INNER JOIN Mastersoft.dbo.PRECIOS pr
        ON pr.Articulo COLLATE Modern_Spanish_CI_AS = p.Articulo COLLATE Modern_Spanish_CI_AS
    INNER JOIN Mastersoft.dbo.ListaPrecioActiva la
        ON la.CodigoLista COLLATE Modern_Spanish_CI_AS = pr.CodigoLista COLLATE Modern_Spanish_CI_AS
       AND la.Rol = 'S';

    -- ---- Clientes (Mastersoft.msoclientes) ----------------------------------
    -- TODO: verificar nombre real de cada columna en msoclientes; se asume
    --       el mismo nombre que la columna destino como punto de partida.
    INSERT INTO dbo.cliente (rut, codigo, razon, direccion, ciudad, giro, telefono, codigo_ruta, codigo_vendedor)
    SELECT
        rut,             -- TODO: verificar nombre real de columna en msoclientes
        codigo,          -- TODO: verificar nombre real de columna en msoclientes
        razon,           -- TODO: verificar nombre real de columna en msoclientes
        direccion,       -- TODO: verificar nombre real de columna en msoclientes
        ciudad,          -- TODO: verificar nombre real de columna en msoclientes
        giro,            -- TODO: verificar nombre real de columna en msoclientes
        telefono,        -- TODO: verificar nombre real de columna en msoclientes
        codigo_ruta,     -- TODO: verificar nombre real de columna en msoclientes
        codigo_vendedor  -- TODO: verificar nombre real de columna en msoclientes
    FROM Mastersoft.dbo.msoclientes;

    -- ---- Vendedores (Mastersoft.msovendedor) --------------------------------
    -- TODO: verificar nombre real de cada columna en msovendedor.
    INSERT INTO dbo.vendedor (rut, codigo, tipo, nombre, ciudad, comuna, direccion, telefono)
    SELECT
        rut,        -- TODO: verificar nombre real de columna en msovendedor
        codigo,     -- TODO: verificar nombre real de columna en msovendedor
        tipo,       -- TODO: verificar nombre real de columna en msovendedor
        nombre,     -- TODO: verificar nombre real de columna en msovendedor
        ciudad,     -- TODO: verificar nombre real de columna en msovendedor
        comuna,     -- TODO: verificar nombre real de columna en msovendedor
        direccion,  -- TODO: verificar nombre real de columna en msovendedor
        telefono    -- TODO: verificar nombre real de columna en msovendedor
    FROM Mastersoft.dbo.msovendedor;

    -- vendedor_ruta: NO se puebla (confirmado). La asignación de rutas a
    -- vendedores se gestiona manualmente/por la app después de esta carga.

    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;
    THROW;
END CATCH
GO
```

- [ ] **Step 2: Verificar balance de la transacción (sin instancia SQL Server, verificación estructural)**

Run: `grep -c "BEGIN TRANSACTION\|COMMIT TRANSACTION\|ROLLBACK TRANSACTION" base_de_datos/deploy_desde_cero/07_poblado_inicial_ventas.sql`
Expected: `3` (un `BEGIN`, un `COMMIT`, un `ROLLBACK`)

- [ ] **Step 3: Verificar que las 7 tablas destino están cubiertas y `vendedor_ruta` NO aparece como INSERT**

Run: `grep -c "^    INSERT INTO dbo\." base_de_datos/deploy_desde_cero/07_poblado_inicial_ventas.sql`
Expected: `7`

Run: `grep -oh "INSERT INTO dbo\.[A-Za-z_]*" base_de_datos/deploy_desde_cero/07_poblado_inicial_ventas.sql | sort`
Expected:
```
INSERT INTO dbo.cliente
INSERT INTO dbo.condicionventa
INSERT INTO dbo.conduccion
INSERT INTO dbo.ila
INSERT INTO dbo.producto
INSERT INTO dbo.ruta
INSERT INTO dbo.vendedor
```

Run: `grep -c "INSERT INTO dbo.vendedor_ruta" base_de_datos/deploy_desde_cero/07_poblado_inicial_ventas.sql`
Expected: `0`

- [ ] **Step 4: Commit**

```bash
git add base_de_datos/deploy_desde_cero/07_poblado_inicial_ventas.sql
git commit -m "feat(db): agrega poblado inicial masivo de ventas desde Mastersoft"
```

---

## Task 9: Verificación final cruzada y nota de limitaciones

**Files:**
- Modify: ninguno (solo verificación)
- Create: nada nuevo

**Interfaces:**
- N/A — tarea de verificación pura.

- [ ] **Step 1: Confirmar que los 9 archivos existen y en el orden correcto**

Run: `ls /Users/cursor/Dev/dipalza/application_v2.0/dipalza_server/base_de_datos/deploy_desde_cero/`
Expected (actualizado tras el fix de la condición de carrera entre `05` y `07`, ver nota de estado al final de este documento — el paquete pasa de 8 a 9 archivos):
```
00_crear_base_datos.sql
01_esquema_ventas.sql
02_listaprecioactiva_fuente.sql
03_colas_triggers_mastersoft.sql
04_procesadores_ventas.sql
05_jobs_msdb.sql
06_configuracion_inicial.sql
07_poblado_inicial_ventas.sql
08_habilitar_jobs.sql
```

- [ ] **Step 2: Confirmar que ningún objeto nombrado en `install_dipalza_sync.sql` quedó fuera de la extracción**

Run:
```bash
cd /Users/cursor/Dev/dipalza/application_v2.0/dipalza_server
grep -oh "CREATE TABLE dbo\.[A-Za-z_]*\|CREATE OR ALTER TRIGGER dbo\.[A-Za-z_]*\|CREATE OR ALTER PROCEDURE dbo\.[A-Za-z_]*" base_de_datos/db/install_dipalza_sync.sql | sort > /tmp/original_objects.txt
grep -oh "CREATE TABLE dbo\.[A-Za-z_]*\|CREATE OR ALTER TRIGGER dbo\.[A-Za-z_]*\|CREATE OR ALTER PROCEDURE dbo\.[A-Za-z_]*" base_de_datos/deploy_desde_cero/0*.sql | sort > /tmp/extracted_objects.txt
diff /tmp/original_objects.txt /tmp/extracted_objects.txt
```
Expected: sin diferencias (output vacío)

- [ ] **Step 3: Confirmar que `install_dipalza_sync.sql` no fue modificado**

Run: `cd /Users/cursor/Dev/dipalza/application_v2.0/dipalza_server && git diff --stat base_de_datos/db/install_dipalza_sync.sql`
Expected: sin output (archivo sin cambios)

- [ ] **Step 4: Actualizar la spec con una nota de estado final**

Editar `docs/superpowers/specs/2026-07-19-deploy-desde-cero-ventas-design.md`, agregar al final de la sección "6. Riesgos / limitaciones conocidas":

```markdown
- **Estado (2026-07-19):** los 9 scripts fueron generados y verificados estructuralmente (conteo de objetos, balance de transacciones), pero **nunca ejecutados contra una instancia SQL Server real** — no había una disponible durante esta sesión. Antes de usarlos en un ambiente real: (1) confirmar columnas reales de `msoclientes`/`msovendedor`, (2) decidir e implementar el cálculo real de `Stock` inicial vía `calcularStock`/`calcularStockNumerado`, (3) definir el origen real de `producto.costo`, (4) ejecutar en un ambiente de prueba antes de producción.
```

- [ ] **Step 5: Commit final**

```bash
cd /Users/cursor/Dev/dipalza/application_v2.0/dipalza_server
git add docs/superpowers/specs/2026-07-19-deploy-desde-cero-ventas-design.md
git commit -m "docs: agrega nota de estado y limitaciones al spec de deploy desde cero"
```

---

## Nota post-revisión: 9º archivo agregado para corregir condición de carrera

Una revisión final de código sobre toda la rama detectó un hallazgo "Important": con el
orden 00→07 original, `05_jobs_msdb.sql` creaba los 4 jobs con `@enabled = 1`, y
`07_poblado_inicial_ventas.sql` corría después dentro de una única transacción. Si
`Mastersoft` estaba recibiendo escrituras durante la ventana del deploy, los triggers de
`03_colas_triggers_mastersoft.sql` encolaban eventos y los procesadores de
`04_procesadores_ventas.sql` (ya habilitados) podían hacer `MERGE` sobre `dbo.producto`,
`dbo.ruta`, etc. al mismo tiempo que `07` hacía sus `INSERT` — riesgo de violación de PK
que aborta todo el poblado inicial, o deadlock.

**Resolución (confirmada por el usuario):** los 4 jobs ahora se crean deshabilitados
(`@enabled = 0` en `05_jobs_msdb.sql`) y se agregó un noveno archivo,
`08_habilitar_jobs.sql`, que los habilita explícitamente vía `sp_update_job` — a
ejecutar solo después de que `07` haya terminado con éxito (commit sin error) y de que
el seed manual de `ListaPrecioActiva` de `06` ya esté aplicado. El paquete pasa de 8 a 9
archivos; el orden de ejecución sigue siendo estrictamente secuencial, ahora 00→08.
También se agregó una nota aclaratoria en `07` sobre el carácter provisorio del
`VentaNeto` inicial, ya que depende de este mismo orden (06 antes de que el job de
precios, habilitado en 08, corrija el valor).
