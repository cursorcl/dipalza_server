/* ============================================================================
   INSTALACIÓN DESDE CERO  —  Sincronización Mastersoft  <->  ventas (Dipalza)
   ----------------------------------------------------------------------------
   Script único ejecutable de punta a punta. Incluye:
     - Esquema completo de la app (base [ventas])
     - Sincronización de STOCK  (colas + triggers + procesador + job)
     - Sincronización de TABLAS MAESTRAS (idem)
     - Sincronización de PRECIOS (idem)  <-- agregada
   con las correcciones de transacción aplicadas a los tres procesadores.

   TOPOLOGÍA
     [Mastersoft] = base del ERP. Ya contiene las tablas operacionales/maestras
                    (invdetallepartes, detalledocumento, encabezadocumento,
                     msosttablas, ARTICULO, articulosnumerados, PRECIOS, ...).
                    Aquí se crean las COLAS, la config de listas y los TRIGGERS.
     [ventas]     = base de la app Dipalza. Aquí se crea TODO el esquema de la
                    app y los PROCEDIMIENTOS que consumen las colas y escriben
                    sobre las tablas locales (producto, ruta, etc.).
     [msdb]       = SQL Server Agent. Aquí se crean los JOBS.

   CRUCES DE BASE (todos en la misma instancia -> NO requieren MSDTC)
     - Los procesadores (en [ventas]) LEEN de [Mastersoft] (colas, maestras, PRECIOS).
     - El trigger de resync de precios (en [Mastersoft]) ESCRIBE directo en
       [ventas].[dbo].[producto] solo en la rama de "todo a 0".

   PRERREQUISITOS
     - Las bases [Mastersoft] y [ventas] ya existen.
     - En [Mastersoft] ya existen las tablas del ERP a las que se enganchan los
       triggers (invdetallepartes, detalledocumento, encabezadocumento,
       msosttablas, PRECIOS) y las que leen los procesadores (ARTICULO,
       articulosnumerados).
     - SQL Server Agent activo.

   ORDEN DE EJECUCIÓN
     1) Esquema completo de [ventas] + datos semilla
     2) Colas, config y triggers en [Mastersoft]
     3) Procedimientos en [ventas] (con transacción)
     4) Jobs en [msdb]
     5) Configuración inicial de listas de precio (comentado) + verificación

   CORRECCIONES RESPECTO A LOS DOCUMENTOS ORIGINALES
     [TX]  Los 3 procesadores se envuelven en una sola transacción
           (SET XACT_ABORT ON + TRY/CATCH + ROLLBACK + THROW): si falla la
           aplicación o la limpieza, se deshace también el claim (ProcessedAt)
           y los eventos se reintentan en el siguiente ciclo (idempotente).
     [FIX] Se eliminó "CREATE SCHEMA dbo;" (dbo siempre existe -> daría error).
     [FIX] Índices normalizados: sin el filegroup roto "ON [PRIMARY ]" ni las
           opciones WITH() por defecto. El índice sobre geography pasó a
           CREATE SPATIAL INDEX.
     [FIX] Nombres de constraint legibles (los originales eran hashes del volcado).
     [FIX] PrecioLista2 ahora está DENTRO del CREATE TABLE producto.
   ============================================================================ */


/* ============================================================================
   SECCIÓN 1 — ESQUEMA DE LA BASE [ventas] + DATOS SEMILLA
   (consolida migration_20260217.sql, migration_20260529.sql y users.sql)
   ============================================================================ */
USE ventas;
GO

CREATE TABLE dbo.app_role (
    id   bigint IDENTITY(1,1) NOT NULL,
    name varchar(50) COLLATE Modern_Spanish_CI_AS NOT NULL,
    CONSTRAINT PK_app_role PRIMARY KEY (id),
    CONSTRAINT UQ_app_role_name UNIQUE (name)
);

CREATE TABLE dbo.app_user (
    id         bigint IDENTITY(1,1) NOT NULL,
    username   varchar(100) COLLATE Modern_Spanish_CI_AS NOT NULL,
    password   varchar(100) COLLATE Modern_Spanish_CI_AS NOT NULL,
    enabled    bit  NOT NULL DEFAULT 1,
    locked     bit  NOT NULL DEFAULT 0,
    created_at date NOT NULL DEFAULT CONVERT(date, SYSUTCDATETIME()),
    updated_at date NOT NULL DEFAULT CONVERT(date, SYSUTCDATETIME()),
    CONSTRAINT PK_app_user PRIMARY KEY (id),
    CONSTRAINT UQ_app_user_username UNIQUE (username)
);

CREATE TABLE dbo.cliente (
    rut             varchar(10) COLLATE Modern_Spanish_CI_AS NOT NULL,
    codigo          varchar(3)  COLLATE Modern_Spanish_CI_AS NOT NULL,
    razon           varchar(60) COLLATE Modern_Spanish_CI_AS NULL,
    direccion       varchar(40) COLLATE Modern_Spanish_CI_AS NULL,
    ciudad          varchar(30) COLLATE Modern_Spanish_CI_AS NULL,
    giro            varchar(40) COLLATE Modern_Spanish_CI_AS NULL,
    telefono        varchar(40) COLLATE Modern_Spanish_CI_AS NULL,
    codigo_ruta     varchar(10) COLLATE Modern_Spanish_CI_AS NULL,
    codigo_vendedor varchar(3)  COLLATE Modern_Spanish_CI_AS NULL,
    CONSTRAINT cliente_pk PRIMARY KEY (rut, codigo)
);

CREATE TABLE dbo.condicionventa (
    codigo      varchar(10) COLLATE Modern_Spanish_CI_AS NOT NULL,
    descripcion varchar(50) COLLATE Modern_Spanish_CI_AS NOT NULL,
    dias        int NULL,
    CONSTRAINT condicionventa_pk PRIMARY KEY (codigo)
);

CREATE TABLE dbo.conduccion (
    codigo      varchar(10) COLLATE Modern_Spanish_CI_AS NOT NULL,
    descripcion varchar(50) COLLATE Modern_Spanish_CI_AS NOT NULL,
    valor       money NULL,
    CONSTRAINT conduccion_pk PRIMARY KEY (codigo)
);

CREATE TABLE dbo.configuracion (
    propiedad   varchar(255) COLLATE Modern_Spanish_CI_AS NOT NULL,
    valor       varchar(255) COLLATE Modern_Spanish_CI_AS NULL,
    tipo        varchar(100) COLLATE Modern_Spanish_CI_AS NULL,
    descripcion varchar(255) COLLATE Modern_Spanish_CI_AS NULL,
    CONSTRAINT configuracion_pk PRIMARY KEY (propiedad)
);

CREATE TABLE dbo.historial_posicion (
    id         bigint IDENTITY(1,1) NOT NULL,
    vendedorId varchar(3) COLLATE Modern_Spanish_CI_AS NOT NULL,
    fechaHora  datetime2(0) NOT NULL,
    posicion   geography NOT NULL,
    CONSTRAINT pk_historial_posicion PRIMARY KEY (id)
);

CREATE TABLE dbo.ila (
    codigo      varchar(10) COLLATE Modern_Spanish_CI_AS NOT NULL,
    descripcion varchar(50) COLLATE Modern_Spanish_CI_AS NOT NULL,
    valor       money NULL,
    CONSTRAINT ila_pk PRIMARY KEY (codigo)
);

CREATE TABLE dbo.posicion (
    vendedorId          varchar(3) COLLATE Modern_Spanish_CI_AS NOT NULL,
    posicion            geography NOT NULL,
    ultimaActualizacion datetime2(0) NOT NULL,
    CONSTRAINT posicion_pk PRIMARY KEY (vendedorId)
);

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

CREATE TABLE dbo.ruta (
    codigo        varchar(10)  COLLATE Modern_Spanish_CI_AS NOT NULL,
    descripcion   varchar(50)  COLLATE Modern_Spanish_CI_AS NOT NULL,
    conduccion_id varchar(100) COLLATE Modern_Spanish_CI_AS NULL,
    CONSTRAINT ruta_pk PRIMARY KEY (codigo)
);

CREATE TABLE dbo.vendedor (
    rut       varchar(10) COLLATE Modern_Spanish_CI_AS NULL,
    codigo    varchar(3)  COLLATE Modern_Spanish_CI_AS NOT NULL,
    tipo      varchar(1)  COLLATE Modern_Spanish_CI_AS NOT NULL,
    nombre    varchar(40) COLLATE Modern_Spanish_CI_AS NULL,
    ciudad    varchar(30) COLLATE Modern_Spanish_CI_AS NULL,
    comuna    varchar(30) COLLATE Modern_Spanish_CI_AS NULL,
    direccion varchar(40) COLLATE Modern_Spanish_CI_AS NULL,
    telefono  varchar(40) COLLATE Modern_Spanish_CI_AS NULL,
    CONSTRAINT vendedor_pk PRIMARY KEY (codigo, tipo)
);

CREATE TABLE dbo.vendedor_ruta (
    codigo_vendedor varchar(3)  COLLATE Modern_Spanish_CI_AS NOT NULL,
    tipo_vendedor   varchar(1)  COLLATE Modern_Spanish_CI_AS NOT NULL,
    codigo_ruta     varchar(10) COLLATE Modern_Spanish_CI_AS NOT NULL,
    CONSTRAINT PK_vendedor_ruta PRIMARY KEY (codigo_vendedor, tipo_vendedor, codigo_ruta),
    CONSTRAINT FK_vendedor_ruta_vendedor FOREIGN KEY (codigo_vendedor, tipo_vendedor) REFERENCES dbo.vendedor(codigo, tipo),
    CONSTRAINT FK_vendedor_ruta_ruta FOREIGN KEY (codigo_ruta) REFERENCES dbo.ruta(codigo)
);

CREATE TABLE dbo.app_refresh_token (
    id         bigint IDENTITY(1,1) NOT NULL,
    user_id    bigint NOT NULL,
    token_hash varchar(200) COLLATE Modern_Spanish_CI_AS NOT NULL,
    expires_at datetime NOT NULL,
    revoked    bit NOT NULL DEFAULT 0,
    created_at datetime NOT NULL DEFAULT GETDATE(),
    CONSTRAINT PK_app_refresh_token PRIMARY KEY (id),
    CONSTRAINT UQ_app_refresh_token UNIQUE (user_id, token_hash),
    CONSTRAINT FK_app_refresh_token_user FOREIGN KEY (user_id) REFERENCES dbo.app_user(id)
);

CREATE TABLE dbo.app_user_roles (
    user_id bigint NOT NULL,
    role_id bigint NOT NULL,
    CONSTRAINT PK_app_user_roles PRIMARY KEY (user_id, role_id),
    CONSTRAINT FK_app_user_roles_role FOREIGN KEY (role_id) REFERENCES dbo.app_role(id),
    CONSTRAINT FK_app_user_roles_user FOREIGN KEY (user_id) REFERENCES dbo.app_user(id)
);

CREATE TABLE dbo.numerados (
    id             bigint IDENTITY(1,1) NOT NULL,
    articulo       varchar(255) COLLATE Modern_Spanish_CI_AS NOT NULL,
    numero         int NOT NULL,
    peso           decimal(19,4) NOT NULL,
    estado         char(1) COLLATE Modern_Spanish_CI_AS NOT NULL DEFAULT 'D',
    creado_en      date NOT NULL DEFAULT CONVERT(date, SYSUTCDATETIME()),
    actualizado_en date NOT NULL DEFAULT CONVERT(date, SYSUTCDATETIME()),
    CONSTRAINT PK_InventarioNumerados PRIMARY KEY (id),
    CONSTRAINT FK_InvNum_producto FOREIGN KEY (articulo) REFERENCES dbo.producto(Articulo),
    CONSTRAINT CK_InvNum_estado CHECK (estado IN ('A','V','R','D')),
    CONSTRAINT CK_InvNum_peso   CHECK (peso >= 0)
);

CREATE TABLE dbo.venta (
    id              bigint IDENTITY(1,1) NOT NULL,
    rut_cliente     varchar(10) COLLATE Modern_Spanish_CI_AS NOT NULL,
    codigo_cliente  varchar(3)  COLLATE Modern_Spanish_CI_AS NOT NULL,
    codigo_vendedor varchar(3)  COLLATE Modern_Spanish_CI_AS NOT NULL,
    tipo_vendedor   varchar(1)  COLLATE Modern_Spanish_CI_AS NOT NULL,
    fecha           datetime NOT NULL,
    codigo_ruta     varchar(10) COLLATE Modern_Spanish_CI_AS NOT NULL,
    total_descuento money NULL,
    total_iva       money NULL,
    total_ila       money NULL,
    total           money NULL,
    estado          varchar(20) COLLATE Modern_Spanish_CI_AS NULL,
    condicion_venta varchar(10) COLLATE Modern_Spanish_CI_AS NULL,
    total_neto      money NULL,
    CONSTRAINT venta_pk PRIMARY KEY (id),
    CONSTRAINT venta_cliente_FK FOREIGN KEY (rut_cliente, codigo_cliente)
        REFERENCES dbo.cliente(rut, codigo) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT venta_condicionventa_FK FOREIGN KEY (condicion_venta)
        REFERENCES dbo.condicionventa(codigo) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT venta_ruta_FK FOREIGN KEY (codigo_ruta)
        REFERENCES dbo.ruta(codigo),
    CONSTRAINT venta_vendedor_FK FOREIGN KEY (codigo_vendedor, tipo_vendedor)
        REFERENCES dbo.vendedor(codigo, tipo)
);

CREATE TABLE dbo.venta_detalle (
    id              bigint IDENTITY(1,1) NOT NULL,
    venta_id        bigint NOT NULL,
    producto_id     varchar(255) COLLATE Modern_Spanish_CI_AS NOT NULL,
    cantidad        decimal(18,4) NOT NULL,
    precio_unitario decimal(18,6) NOT NULL,
    porc_descuento  decimal(18,2) NOT NULL,
    total_descuento decimal(18,2) NOT NULL,
    porc_ila        decimal(18,2) NOT NULL,
    total_ila       decimal(18,2) NOT NULL,
    porc_iva        decimal(18,2) NOT NULL,
    total_iva       decimal(18,2) NOT NULL,
    total_linea     decimal(18,2) NOT NULL,
    piezas          int NOT NULL DEFAULT 0,
    unidad          varchar(10) COLLATE Modern_Spanish_CI_AS NULL,
    CONSTRAINT venta_detalle_pk PRIMARY KEY (id),
    CONSTRAINT FK_vd_producto FOREIGN KEY (producto_id) REFERENCES dbo.producto(Articulo),
    CONSTRAINT FK_vd_venta    FOREIGN KEY (venta_id)    REFERENCES dbo.venta(id)
);

CREATE TABLE dbo.venta_detalle_pieza (
    id               bigint IDENTITY(1,1) NOT NULL,
    id_detalle_venta bigint NULL,
    inv_id_pieza     bigint NULL,
    peso             decimal(19,4) NOT NULL,
    creado_en        date NOT NULL DEFAULT CONVERT(date, SYSUTCDATETIME()),
    CONSTRAINT PK_venta_detalle_pieza PRIMARY KEY (id),
    CONSTRAINT venta_detalle_pieza_numerados_FK FOREIGN KEY (inv_id_pieza)
        REFERENCES dbo.numerados(id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT venta_detalle_pieza_venta_detalle_FK FOREIGN KEY (id_detalle_venta)
        REFERENCES dbo.venta_detalle(id) ON DELETE CASCADE ON UPDATE CASCADE
);

-- ---- Índices --------------------------------------------------------------
CREATE UNIQUE NONCLUSTERED INDEX configuracion_propiedad_IDX
    ON dbo.configuracion (propiedad);

CREATE NONCLUSTERED INDEX idx_histotial_vendedor_fechaHora
    ON dbo.historial_posicion (vendedorId, fechaHora);

CREATE SPATIAL INDEX sidx_historial_posicion
    ON dbo.historial_posicion (posicion);

CREATE NONCLUSTERED INDEX venta_codigo_vendedor_IDX
    ON dbo.venta (codigo_vendedor, tipo_vendedor);
CREATE NONCLUSTERED INDEX venta_fecha_IDX
    ON dbo.venta (fecha);
CREATE NONCLUSTERED INDEX venta_rut_cliente_IDX
    ON dbo.venta (rut_cliente);

CREATE NONCLUSTERED INDEX IX_vd_producto ON dbo.venta_detalle (producto_id);
CREATE NONCLUSTERED INDEX IX_vd_venta    ON dbo.venta_detalle (venta_id);

-- ---- Datos semilla (de users.sql) -----------------------------------------
INSERT INTO dbo.app_role (name) VALUES ('ROLE_ADMIN'), ('ROLE_VENDEDOR');
GO

-- ---- Sincronización inversa de listas activas: FUENTE en [ventas] ---------
CREATE TABLE dbo.ListaPrecioActiva (
    Rol         CHAR(1) COLLATE Modern_Spanish_CI_AS      NOT NULL,                 -- 'P' principal (->VentaNeto), 'S' secundaria (->PrecioLista2)
    CodigoLista VARCHAR(3) COLLATE Modern_Spanish_CI_AS   NOT NULL,
    UpdatedAt   DATETIME2(7) NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_ListaPrecioActiva       PRIMARY KEY (Rol),
    CONSTRAINT CK_ListaPrecioActiva_Rol   CHECK (Rol IN ('P','S')),
    CONSTRAINT UQ_ListaPrecioActiva_Lista UNIQUE (CodigoLista)
);

CREATE TABLE dbo.ListaPrecioActivaQueue (
    EventID     BIGINT IDENTITY(1,1) PRIMARY KEY,
    Rol         CHAR(1) COLLATE Modern_Spanish_CI_AS      NOT NULL,
    ChangeType  CHAR(1) COLLATE Modern_Spanish_CI_AS      NOT NULL,   -- 'I' / 'U' / 'D' (informativo)
    CreatedAt   DATETIME2(7) NOT NULL DEFAULT SYSUTCDATETIME(),
    ProcessedAt DATETIME2(7) NULL
);
CREATE INDEX IX_ListaPrecioActivaQueue_ForProcessing
    ON dbo.ListaPrecioActivaQueue (ProcessedAt, CreatedAt);
GO

CREATE OR ALTER TRIGGER dbo.trg_listaprecioactiva_outbox
    ON dbo.ListaPrecioActiva
    AFTER INSERT, UPDATE, DELETE
AS
BEGIN
    SET NOCOUNT ON;
    INSERT INTO dbo.ListaPrecioActivaQueue (Rol, ChangeType)
    SELECT COALESCE(i.Rol, d.Rol) AS Rol,
           CASE WHEN i.Rol IS NOT NULL AND d.Rol IS NOT NULL THEN 'U'
                WHEN i.Rol IS NOT NULL THEN 'I'
                ELSE 'D' END AS ChangeType
    FROM inserted i
             FULL OUTER JOIN deleted d ON i.Rol = d.Rol;
END;
GO

CREATE OR ALTER TRIGGER dbo.trg_listaprecioactiva_guard
    ON dbo.ListaPrecioActiva
    AFTER INSERT, UPDATE, DELETE
AS
BEGIN
    SET NOCOUNT ON;
    IF NOT EXISTS (SELECT 1 FROM dbo.ListaPrecioActiva WHERE Rol = 'P')
        BEGIN
            ROLLBACK TRANSACTION;
            THROW 50002, 'ventas.ListaPrecioActiva debe tener siempre una lista principal (Rol = ''P''). Operacion cancelada.', 1;
        END
END;
GO

-- ---- Trigger de stockVentas/piezasVentas (reserva al vender, libera al facturar
--      o al eliminar la venta/detalle mientras no esté CLOSED). Acumula "vendido
--      pendiente de facturar"; el descuento al facturar lo hace la app
--      (VentaItemPorcessorNoNumerado/VentaItemProcessorNumerado). El modelo de
--      estados de Venta es solo OPENED/FINISHED/CLOSED (no existe "cancelar": el
--      vendedor simplemente elimina la venta/detalle mientras la construye, lo que
--      dispara la rama DELETE de este mismo trigger).
CREATE OR ALTER TRIGGER dbo.tgr_ventadetalle_producto
    ON dbo.venta_detalle
    AFTER INSERT, UPDATE, DELETE
AS
BEGIN
    SET NOCOUNT ON;
    ;WITH base AS (
        -- 1. Lo que entra (INSERTED) se agrega a la cantidad vendida
        SELECT i.producto_id, i.cantidad AS cantidad , i.piezas as piezas
        FROM inserted i
        INNER JOIN dbo.venta v ON v.id = i.venta_id
        WHERE v.estado <> 'CLOSED'

        UNION ALL

        -- 2. Lo que sale (DELETED) es venta resta a la cantidad vendida
        SELECT d.producto_id, -d.cantidad , -d.piezas
        FROM deleted d
        INNER JOIN dbo.venta v ON v.id = d.venta_id
        WHERE v.estado <> 'CLOSED'
    ),
    agg AS (
        SELECT  producto_id,
                SUM(cantidad) AS delta_stock,
                SUM(piezas) as delta_piezas
        FROM base
        GROUP BY producto_id
        HAVING SUM(cantidad) <> 0 OR SUM(piezas) <> 0
    )
    UPDATE p
    SET p.stockVentas = p.stockVentas + agg.delta_stock, p.piezasVentas = p.piezasVentas + agg.delta_piezas
    FROM dbo.producto p
    INNER JOIN agg ON p.articulo = agg.producto_id;
END;
GO


/* ============================================================================
   SECCIÓN 2 — COLAS, CONFIG Y TRIGGERS EN [Mastersoft]
   (de actualiza_stock.sql, tablas_maestras.sql y la sincronización de precios)
   ============================================================================ */
USE Mastersoft;
GO

-- ---- Cola de STOCK --------------------------------------------------------
CREATE TABLE dbo.StockUpdateQueue (
    EventID     BIGINT IDENTITY(1,1) PRIMARY KEY,
    Articulo    NVARCHAR(50) COLLATE Modern_Spanish_CI_AS   NOT NULL,
    DeltaStock  DECIMAL(18,4)  NOT NULL,
    CreatedAt   DATETIME2(7)   NOT NULL DEFAULT SYSUTCDATETIME(),
    ProcessedAt DATETIME2(7)   NULL
);
CREATE INDEX IX_StockUpdateQueue_ForProcessing
    ON dbo.StockUpdateQueue (ProcessedAt, CreatedAt);

-- ---- Cola de TABLAS MAESTRAS ----------------------------------------------
CREATE TABLE dbo.MasterDataUpdateQueue (
    EventID     BIGINT IDENTITY(1,1) PRIMARY KEY,
    TableName   NVARCHAR(128) COLLATE Modern_Spanish_CI_AS NOT NULL,
    PrimaryKey  NVARCHAR(255) COLLATE Modern_Spanish_CI_AS NOT NULL,
    ChangeType  CHAR(1) COLLATE Modern_Spanish_CI_AS       NOT NULL,   -- 'I' / 'U' / 'D'
    CreatedAt   DATETIME2(7)  NOT NULL DEFAULT SYSUTCDATETIME(),
    ProcessedAt DATETIME2(7)  NULL
);
CREATE INDEX IX_MasterDataUpdateQueue_ForProcessing
    ON dbo.MasterDataUpdateQueue (ProcessedAt, CreatedAt);

-- ---- Config de LISTAS DE PRECIO ACTIVAS -----------------------------------
CREATE TABLE dbo.ListaPrecioActiva (
    Rol         CHAR(1) COLLATE Modern_Spanish_CI_AS      NOT NULL,  -- 'P' = principal (->VentaNeto), 'S' = secundaria (->PrecioLista2)
    CodigoLista VARCHAR(3) COLLATE Modern_Spanish_CI_AS   NOT NULL,
    UpdatedAt   DATETIME2(7) NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_ListaPrecioActiva       PRIMARY KEY (Rol),
    CONSTRAINT CK_ListaPrecioActiva_Rol   CHECK (Rol IN ('P','S')),
    CONSTRAINT UQ_ListaPrecioActiva_Lista UNIQUE (CodigoLista)
);

-- ---- Cola de PRECIOS ------------------------------------------------------
CREATE TABLE dbo.PriceUpdateQueue (
    EventID     BIGINT IDENTITY(1,1) PRIMARY KEY,
    Articulo    VARCHAR(15) COLLATE Modern_Spanish_CI_AS  NOT NULL,   -- mismo tipo/largo que PRECIOS.Articulo
    Rol         CHAR(1) COLLATE Modern_Spanish_CI_AS      NOT NULL,   -- 'P' o 'S': a qué campo de producto va
    CreatedAt   DATETIME2(7) NOT NULL DEFAULT SYSUTCDATETIME(),
    ProcessedAt DATETIME2(7) NULL
);
CREATE INDEX IX_PriceUpdateQueue_ForProcessing
    ON dbo.PriceUpdateQueue (ProcessedAt, CreatedAt);
GO

-- ---- Triggers de STOCK ----------------------------------------------------
CREATE OR ALTER TRIGGER dbo.trg_invdetallepartes_stockresumen
    ON dbo.invdetallepartes
    AFTER INSERT, UPDATE, DELETE
AS
BEGIN
    SET NOCOUNT ON;
    ;WITH base AS (
        SELECT i.articulo, i.[Local] AS [local], i.Tipoid, i.cantidad AS cant
        FROM inserted i
        UNION ALL
        SELECT d.articulo, d.[Local] AS [local], d.Tipoid, -d.cantidad AS cant
        FROM deleted d
    ),
    agg AS (
        SELECT articulo,
               SUM(CASE WHEN Tipoid = 17 THEN cant
                        WHEN Tipoid = 18 THEN -cant
                        ELSE 0 END) AS delta_stock
        FROM base
        WHERE [local] = '000'
        GROUP BY articulo
    )
    INSERT INTO [Mastersoft].[dbo].[StockUpdateQueue] (Articulo, DeltaStock)
    SELECT articulo, delta_stock
    FROM agg
    WHERE delta_stock <> 0;
END;
GO

CREATE OR ALTER TRIGGER dbo.trg_detalledocumento_stockresumen
    ON dbo.detalledocumento
    AFTER INSERT, UPDATE, DELETE
AS
BEGIN
    SET NOCOUNT ON;
    ;WITH base AS (
        SELECT d.articulo, d.[local], d.tipoid, d.cantidad AS cant, e.vigente
        FROM inserted d
                 JOIN dbo.encabezadocumento e ON e.id = d.id
        UNION ALL
        SELECT d.articulo, d.[local], d.tipoid, -d.cantidad AS cant, e.vigente
        FROM deleted d
                 JOIN dbo.encabezadocumento e ON e.id = d.id
    ),
    agg AS (
        SELECT articulo,
               SUM(CASE WHEN vigente = 1 AND tipoid = '09' THEN cant
                        WHEN vigente = 1 AND tipoid IN ('06','10') THEN -cant
                        ELSE 0 END) AS delta_stock
        FROM base
        WHERE [local] = '000'
        GROUP BY articulo
    )
    INSERT INTO [Mastersoft].[dbo].[StockUpdateQueue] (Articulo, DeltaStock)
    SELECT articulo, delta_stock
    FROM agg
    WHERE delta_stock <> 0;
END;
GO

CREATE OR ALTER TRIGGER dbo.trg_encabezadocumento_stockresumen_vigente
    ON dbo.encabezadocumento
    AFTER UPDATE
AS
BEGIN
    SET NOCOUNT ON;
    IF NOT UPDATE(vigente) RETURN;   -- solo actuar si cambió la vigencia

    ;WITH chg AS (
        SELECT i.id,
               ISNULL(d.vigente, 0) AS vigente_old,
               ISNULL(i.vigente, 0) AS vigente_new
        FROM inserted i
                 INNER JOIN deleted d ON d.id = i.id
        WHERE ISNULL(d.vigente, 0) <> ISNULL(i.vigente, 0)
    ),
    det AS (
        SELECT d.articulo, d.[local], d.tipoid, d.cantidad, c.vigente_old, c.vigente_new
        FROM chg c
                 JOIN dbo.detalledocumento d ON d.id = c.id
        WHERE d.[local] = '000'
    ),
    agg AS (
        SELECT articulo,
               SUM(CASE
                       WHEN vigente_old = 0 AND vigente_new = 1 THEN
                           CASE WHEN tipoid = '09' THEN cantidad
                                WHEN tipoid IN ('06','10') THEN -cantidad ELSE 0 END
                       WHEN vigente_old = 1 AND vigente_new = 0 THEN
                           CASE WHEN tipoid = '09' THEN -cantidad
                                WHEN tipoid IN ('06','10') THEN cantidad ELSE 0 END
                       ELSE 0
                   END) AS delta_stock
        FROM det
        GROUP BY articulo
    )
    INSERT INTO [Mastersoft].[dbo].[StockUpdateQueue] (Articulo, DeltaStock)
    SELECT articulo, delta_stock
    FROM agg
    WHERE delta_stock <> 0;
END;
GO

-- ---- Trigger de TABLAS MAESTRAS -------------------------------------------
CREATE OR ALTER TRIGGER dbo.trg_msosttablas_sync
    ON dbo.msosttablas
    AFTER INSERT, UPDATE, DELETE
AS
BEGIN
    SET NOCOUNT ON;

    -- INSERCIONES
    IF EXISTS (SELECT * FROM inserted) AND NOT EXISTS (SELECT * FROM deleted)
        BEGIN
            INSERT INTO dbo.MasterDataUpdateQueue (TableName, PrimaryKey, ChangeType)
            SELECT 'msosttablas', i.tabla + '|' + i.codigo, 'I'
            FROM inserted i;
        END

    -- ACTUALIZACIONES
    IF EXISTS (SELECT * FROM inserted) AND EXISTS (SELECT * FROM deleted)
        BEGIN
            INSERT INTO dbo.MasterDataUpdateQueue (TableName, PrimaryKey, ChangeType)
            SELECT 'msosttablas', i.tabla + '|' + i.codigo, 'U'
            FROM inserted i;
        END

    -- ELIMINACIONES
    IF NOT EXISTS (SELECT * FROM inserted) AND EXISTS (SELECT * FROM deleted)
        BEGIN
            INSERT INTO dbo.MasterDataUpdateQueue (TableName, PrimaryKey, ChangeType)
            SELECT 'msosttablas', d.tabla + '|' + d.codigo, 'D'
            FROM deleted d;
        END
END;
GO

-- ---- Trigger de PRECIOS (alimenta PriceUpdateQueue) -----------------------
CREATE OR ALTER TRIGGER dbo.trg_precios_priceupdate
    ON dbo.PRECIOS
    AFTER INSERT, UPDATE, DELETE
AS
BEGIN
    SET NOCOUNT ON;

    DECLARE @hasIns BIT = CASE WHEN EXISTS (SELECT 1 FROM inserted) THEN 1 ELSE 0 END;
    DECLARE @hasDel BIT = CASE WHEN EXISTS (SELECT 1 FROM deleted)  THEN 1 ELSE 0 END;

    -- INSERT o UPDATE: encolar SOLO el lado nuevo, y solo si
    -- (Articulo, CodigoLista, VentaNeto) realmente cambió (elimina ruido).
    IF @hasIns = 1
        BEGIN
            ;WITH nuevos AS (
                SELECT Articulo, CodigoLista, VentaNeto FROM inserted
                EXCEPT
                SELECT Articulo, CodigoLista, VentaNeto FROM deleted
            )
            INSERT INTO dbo.PriceUpdateQueue (Articulo, Rol)
            SELECT DISTINCT n.Articulo, la.Rol
            FROM nuevos n
                     INNER JOIN dbo.ListaPrecioActiva la
                                ON la.CodigoLista = n.CodigoLista COLLATE Modern_Spanish_CI_AS;
        END

    -- DELETE puro: encolar el artículo borrado, si su lista estaba activa.
    IF @hasIns = 0 AND @hasDel = 1
        BEGIN
            INSERT INTO dbo.PriceUpdateQueue (Articulo, Rol)
            SELECT DISTINCT d.Articulo, la.Rol
            FROM deleted d
                     INNER JOIN dbo.ListaPrecioActiva la
                                ON la.CodigoLista = d.CodigoLista COLLATE Modern_Spanish_CI_AS;
        END
END;
GO

-- ---- Trigger de RESYNC sobre ListaPrecioActiva ----------------------------
CREATE OR ALTER TRIGGER dbo.trg_listaprecioactiva_resync
    ON dbo.ListaPrecioActiva
    AFTER INSERT, UPDATE, DELETE
AS
BEGIN
    SET NOCOUNT ON;

    -- (1) Activación o cambio de lista: re-encolar los artículos de la lista NUEVA.
    ;WITH nueva_lista AS (
        SELECT i.Rol, i.CodigoLista
        FROM inserted i
                 LEFT JOIN deleted d ON d.Rol = i.Rol
        WHERE d.Rol IS NULL                       -- activación (rol no existía)
           OR d.CodigoLista <> i.CodigoLista      -- cambio de lista
    )
    INSERT INTO dbo.PriceUpdateQueue (Articulo, Rol)
    SELECT DISTINCT p.Articulo, nl.Rol
    FROM nueva_lista nl
             INNER JOIN dbo.PRECIOS p
                        ON p.CodigoLista = nl.CodigoLista COLLATE Modern_Spanish_CI_AS;

    -- (2) Si tras la operación ya NO existe lista 'S' activa: todos los
    --     PrecioLista2 a 0, directo e inmediato (cruza a [ventas]).
    IF EXISTS (SELECT 1 FROM deleted WHERE Rol = 'S')
       AND NOT EXISTS (SELECT 1 FROM dbo.ListaPrecioActiva WHERE Rol = 'S')
        BEGIN
            UPDATE [ventas].[dbo].[producto]
            SET PrecioLista2 = 0
            WHERE PrecioLista2 IS NULL OR PrecioLista2 <> 0;
        END
END;
GO

-- ---- Guard sobre ListaPrecioActiva (al menos una 'P') ---------------------
CREATE OR ALTER TRIGGER dbo.trg_listaprecioactiva_guard
    ON dbo.ListaPrecioActiva
    AFTER INSERT, UPDATE, DELETE
AS
BEGIN
    SET NOCOUNT ON;

    -- Tras cualquier operación, debe existir la lista principal (Rol = 'P').
    IF NOT EXISTS (SELECT 1 FROM dbo.ListaPrecioActiva WHERE Rol = 'P')
        BEGIN
            ROLLBACK TRANSACTION;
            THROW 50001, 'ListaPrecioActiva debe tener siempre una lista principal (Rol = ''P''). Operacion cancelada.', 1;
        END
END;
GO

-- ---- Procesador inverso: espejo ventas -> Mastersoft de ListaPrecioActiva --
CREATE OR ALTER PROCEDURE dbo.usp_ProcessListaPrecioActivaQueue
AS
BEGIN
    SET NOCOUNT ON;
    SET XACT_ABORT ON;

    DECLARE @batchSize INT = 100;
    DECLARE @events TABLE (
        EventID    BIGINT PRIMARY KEY,
        Rol        CHAR(1) COLLATE Modern_Spanish_CI_AS,
        ChangeType CHAR(1) COLLATE Modern_Spanish_CI_AS
    );

    BEGIN TRY
        BEGIN TRANSACTION;

        -- 1. Reclamar lote desde la cola en ventas
        ;WITH cte_batch AS (
            SELECT TOP (@batchSize) EventID, Rol, ChangeType, ProcessedAt
            FROM [ventas].[dbo].[ListaPrecioActivaQueue]
            WHERE ProcessedAt IS NULL
            ORDER BY CreatedAt ASC
        )
        UPDATE cte_batch
           SET ProcessedAt = SYSUTCDATETIME()
        OUTPUT inserted.EventID, inserted.Rol, inserted.ChangeType
            INTO @events (EventID, Rol, ChangeType);

        IF NOT EXISTS (SELECT 1 FROM @events)
            BEGIN
                COMMIT TRANSACTION;
                RETURN;
            END

        -- 2. Sincronización ESPEJO completa (dispara el resync y el guard de Mastersoft)
        MERGE dbo.ListaPrecioActiva AS T
        USING [ventas].[dbo].[ListaPrecioActiva] AS S
            ON T.Rol = S.Rol COLLATE Modern_Spanish_CI_AS
        WHEN MATCHED AND T.CodigoLista <> S.CodigoLista COLLATE Modern_Spanish_CI_AS
            THEN UPDATE SET T.CodigoLista = S.CodigoLista, T.UpdatedAt = SYSUTCDATETIME()
        WHEN NOT MATCHED BY TARGET
            THEN INSERT (Rol, CodigoLista) VALUES (S.Rol, S.CodigoLista)
        WHEN NOT MATCHED BY SOURCE
            THEN DELETE;

        -- 3. Limpieza de la cola
        DELETE FROM [ventas].[dbo].[ListaPrecioActivaQueue]
        WHERE EventID IN (SELECT EventID FROM @events);

        COMMIT TRANSACTION;
    END TRY
    BEGIN CATCH
        IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;
        THROW;
    END CATCH
END;
GO


/* ============================================================================
   SECCIÓN 3 — PROCEDIMIENTOS EN [ventas]  (con transacción)  [TX]
   ============================================================================ */
USE ventas;
GO

-- ---- Procesador de STOCK --------------------------------------------------
CREATE OR ALTER PROCEDURE dbo.usp_ProcessStockUpdateQueue
AS
BEGIN
    SET NOCOUNT ON;
    SET XACT_ABORT ON;

    DECLARE @batchSize INT = 500;

    DECLARE @eventsToProcess TABLE (
        EventID    BIGINT PRIMARY KEY,
        Articulo   NVARCHAR(50) COLLATE Modern_Spanish_CI_AS,
        DeltaStock DECIMAL(18,4)
    );

    BEGIN TRY
        BEGIN TRANSACTION;

        ;WITH cte_batch AS (
            SELECT TOP (@batchSize) EventID, Articulo, DeltaStock, ProcessedAt
            FROM [Mastersoft].[dbo].[StockUpdateQueue]
            WHERE ProcessedAt IS NULL
            ORDER BY CreatedAt ASC
        )
        UPDATE cte_batch
           SET ProcessedAt = SYSUTCDATETIME()
        OUTPUT inserted.EventID, inserted.Articulo, inserted.DeltaStock
            INTO @eventsToProcess (EventID, Articulo, DeltaStock);

        IF NOT EXISTS (SELECT 1 FROM @eventsToProcess)
            BEGIN
                COMMIT TRANSACTION;
                RETURN;
            END

        ;WITH agg_batch AS (
            SELECT Articulo, SUM(DeltaStock) AS FinalDelta
            FROM @eventsToProcess
            GROUP BY Articulo
        ),
        SourceData AS (
            SELECT ab.Articulo, ab.FinalDelta,
                   a.Descripcion, a.VentaNeto, a.PorcIla, a.PorcCarne, a.Unidad, a.CodigoIla,
                   CASE WHEN an.Articulo IS NOT NULL THEN 1 ELSE 0 END AS EsNumerado
            FROM agg_batch ab
                     LEFT JOIN [Mastersoft].[dbo].[ARTICULO] a
                               ON a.Articulo = ab.Articulo COLLATE Modern_Spanish_CI_AS
                     LEFT JOIN [Mastersoft].[dbo].[articulosnumerados] an
                               ON an.Articulo = ab.Articulo COLLATE Modern_Spanish_CI_AS
        )
        MERGE dbo.producto AS T
        USING SourceData AS S
        ON T.Articulo = S.Articulo COLLATE Modern_Spanish_CI_AS
        WHEN MATCHED THEN
            UPDATE SET T.Stock       = COALESCE(T.Stock, 0) + S.FinalDelta,
                       T.last_update = SYSUTCDATETIME()
        WHEN NOT MATCHED BY TARGET AND S.Descripcion IS NOT NULL THEN
            INSERT (Articulo, Descripcion, VentaNeto, PorcIla, PorcCarne, Unidad,
                    Stock, last_update, Numbered, CodigoIla)
            VALUES (S.Articulo, S.Descripcion, S.VentaNeto, S.PorcIla, S.PorcCarne, S.Unidad,
                    S.FinalDelta, SYSUTCDATETIME(), S.EsNumerado, S.CodigoIla);

        DELETE FROM [Mastersoft].[dbo].[StockUpdateQueue]
        WHERE EventID IN (SELECT EventID FROM @eventsToProcess);

        COMMIT TRANSACTION;
    END TRY
    BEGIN CATCH
        IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;
        THROW;
    END CATCH
END;
GO

-- ---- Procesador de TABLAS MAESTRAS ----------------------------------------
CREATE OR ALTER PROCEDURE dbo.usp_ProcessMasterDataQueue
AS
BEGIN
    SET NOCOUNT ON;
    SET XACT_ABORT ON;

    DECLARE @batchSize INT = 100;

    DECLARE @eventsToProcess TABLE (
        EventID    BIGINT PRIMARY KEY,
        TableName  NVARCHAR(128) COLLATE Modern_Spanish_CI_AS,
        PrimaryKey NVARCHAR(255) COLLATE Modern_Spanish_CI_AS,
        ChangeType CHAR(1) COLLATE Modern_Spanish_CI_AS
    );

    BEGIN TRY
        BEGIN TRANSACTION;

        -- 1. Reclamar lote
        ;WITH cte_batch AS (
            SELECT TOP (@batchSize) EventID, TableName, PrimaryKey, ChangeType, ProcessedAt
            FROM [Mastersoft].[dbo].[MasterDataUpdateQueue]
            WHERE ProcessedAt IS NULL
            ORDER BY CreatedAt ASC
        )
        UPDATE cte_batch SET ProcessedAt = SYSUTCDATETIME()
        OUTPUT inserted.EventID, inserted.TableName, inserted.PrimaryKey, inserted.ChangeType
            INTO @eventsToProcess (EventID, TableName, PrimaryKey, ChangeType);

        IF NOT EXISTS (SELECT 1 FROM @eventsToProcess)
            BEGIN
                COMMIT TRANSACTION;
                RETURN;
            END

        -- (La lógica de msoclientes se mantiene igual / pendiente)

        -- 3. Procesar eventos de msosttablas
        IF EXISTS (SELECT 1 FROM @eventsToProcess WHERE TableName = 'msosttablas')
            BEGIN
                IF OBJECT_ID('tempdb..#SourceData') IS NOT NULL DROP TABLE #SourceData;

                SELECT ue.ChangeType, ue.tabla, ue.codigo, s.descripcion, s.valor
                INTO #SourceData
                FROM (
                         SELECT PrimaryKey, ChangeType,
                                LEFT(PrimaryKey, CHARINDEX('|', PrimaryKey) - 1) AS tabla,
                                SUBSTRING(PrimaryKey, CHARINDEX('|', PrimaryKey) + 1, LEN(PrimaryKey)) AS codigo,
                                ROW_NUMBER() OVER (PARTITION BY PrimaryKey ORDER BY EventID DESC) AS rn
                         FROM @eventsToProcess
                         WHERE TableName = 'msosttablas'
                     ) ue
                         LEFT JOIN [Mastersoft].[dbo].[msosttablas] s
                                   ON s.tabla  = ue.tabla  COLLATE Modern_Spanish_CI_AS
                                       AND s.codigo = ue.codigo COLLATE Modern_Spanish_CI_AS
                WHERE ue.rn = 1;

                -- Rutas (017)
                MERGE dbo.ruta AS T
                USING (SELECT * FROM #SourceData WHERE tabla = '017') AS S
                ON (T.codigo = S.codigo COLLATE Modern_Spanish_CI_AS)
                WHEN MATCHED AND S.ChangeType IN ('I','U') AND (T.descripcion <> S.descripcion COLLATE Modern_Spanish_CI_AS)
                    THEN UPDATE SET T.descripcion = S.descripcion
                WHEN MATCHED AND S.ChangeType = 'D' THEN DELETE
                WHEN NOT MATCHED BY TARGET AND S.ChangeType IN ('I','U')
                    THEN INSERT (codigo, descripcion) VALUES (S.codigo, S.descripcion);

                -- Condicion Venta (009)
                MERGE dbo.condicionventa AS T
                USING (SELECT * FROM #SourceData WHERE tabla = '009') AS S
                ON (T.codigo = S.codigo COLLATE Modern_Spanish_CI_AS)
                WHEN MATCHED AND S.ChangeType IN ('I','U') AND (T.descripcion <> S.descripcion COLLATE Modern_Spanish_CI_AS)
                    THEN UPDATE SET T.descripcion = S.descripcion
                WHEN MATCHED AND S.ChangeType = 'D' THEN DELETE
                WHEN NOT MATCHED BY TARGET AND S.ChangeType IN ('I','U')
                    THEN INSERT (codigo, descripcion) VALUES (S.codigo, S.descripcion);

                -- Conducción (015)
                MERGE dbo.conduccion AS T
                USING (SELECT * FROM #SourceData WHERE tabla = '015') AS S
                ON (T.codigo = S.codigo COLLATE Modern_Spanish_CI_AS)
                WHEN MATCHED AND S.ChangeType IN ('I','U') AND (T.descripcion <> S.descripcion OR T.valor <> S.valor)
                    THEN UPDATE SET T.descripcion = S.descripcion, T.valor = S.valor
                WHEN MATCHED AND S.ChangeType = 'D' THEN DELETE
                WHEN NOT MATCHED BY TARGET AND S.ChangeType IN ('I','U')
                    THEN INSERT (codigo, descripcion, valor) VALUES (S.codigo, S.descripcion, S.valor);

                -- ILA (004)
                MERGE dbo.ila AS T
                USING (SELECT * FROM #SourceData WHERE tabla = '004') AS S
                ON (T.codigo = S.codigo COLLATE Modern_Spanish_CI_AS)
                WHEN MATCHED AND S.ChangeType IN ('I','U') AND (T.descripcion <> S.descripcion OR T.valor <> S.valor)
                    THEN UPDATE SET T.descripcion = S.descripcion, T.valor = S.valor
                WHEN MATCHED AND S.ChangeType = 'D' THEN DELETE
                WHEN NOT MATCHED BY TARGET AND S.ChangeType IN ('I','U')
                    THEN INSERT (codigo, descripcion, valor) VALUES (S.codigo, S.descripcion, S.valor);

                DROP TABLE #SourceData;
            END

        -- 4. Limpieza de la cola
        DELETE FROM [Mastersoft].[dbo].[MasterDataUpdateQueue]
        WHERE EventID IN (SELECT EventID FROM @eventsToProcess);

        COMMIT TRANSACTION;
    END TRY
    BEGIN CATCH
        IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;
        THROW;
    END CATCH
END;
GO

-- ---- Procesador de PRECIOS ------------------------------------------------
CREATE OR ALTER PROCEDURE dbo.usp_ProcessPriceUpdateQueue
AS
BEGIN
    SET NOCOUNT ON;
    SET XACT_ABORT ON;

    DECLARE @batchSize INT = 500;

    DECLARE @eventsToProcess TABLE (
        EventID  BIGINT      PRIMARY KEY,
        Articulo VARCHAR(15) COLLATE Modern_Spanish_CI_AS,
        Rol      CHAR(1) COLLATE Modern_Spanish_CI_AS
    );

    BEGIN TRY
        BEGIN TRANSACTION;

        -- 1. Reclamar lote
        ;WITH cte_batch AS (
            SELECT TOP (@batchSize) EventID, Articulo, Rol, ProcessedAt
            FROM [Mastersoft].[dbo].[PriceUpdateQueue]
            WHERE ProcessedAt IS NULL
            ORDER BY CreatedAt ASC
        )
        UPDATE cte_batch
           SET ProcessedAt = SYSUTCDATETIME()
        OUTPUT inserted.EventID, inserted.Articulo, inserted.Rol
            INTO @eventsToProcess (EventID, Articulo, Rol);

        IF NOT EXISTS (SELECT 1 FROM @eventsToProcess)
            BEGIN
                COMMIT TRANSACTION;
                RETURN;
            END

        -- 2. Resolver precios + 3. aplicar a producto
        ;WITH dedup AS (
            SELECT DISTINCT Articulo, Rol FROM @eventsToProcess
        ),
        resolved AS (
            SELECT d.Articulo, d.Rol, ap.NuevoPrecio
            FROM dedup d
            OUTER APPLY (
                -- Precio del artículo en la lista ACTIVA de ese rol.
                -- MIN(): colapso determinista si (por seguridad) hubiera duplicados.
                SELECT MIN(p.VentaNeto) AS NuevoPrecio
                FROM [Mastersoft].[dbo].[ListaPrecioActiva] la
                         INNER JOIN [Mastersoft].[dbo].[PRECIOS] p
                                    ON p.CodigoLista COLLATE Modern_Spanish_CI_AS = la.CodigoLista COLLATE Modern_Spanish_CI_AS
                                        AND p.Articulo COLLATE Modern_Spanish_CI_AS = d.Articulo COLLATE Modern_Spanish_CI_AS
                WHERE la.Rol COLLATE Modern_Spanish_CI_AS = d.Rol COLLATE Modern_Spanish_CI_AS
            ) ap
        ),
        pivoted AS (
            SELECT
                Articulo,
                MAX(CASE WHEN Rol = 'P' THEN 1 ELSE 0 END)                 AS hasP,
                MAX(CASE WHEN Rol = 'P' THEN COALESCE(NuevoPrecio, 0) END) AS precioP,
                MAX(CASE WHEN Rol = 'S' THEN 1 ELSE 0 END)                 AS hasS,
                MAX(CASE WHEN Rol = 'S' THEN COALESCE(NuevoPrecio, 0) END) AS precioS
            FROM resolved
            GROUP BY Articulo
        )
        UPDATE prod
           SET prod.VentaNeto    = CASE WHEN pv.hasP = 1 THEN pv.precioP ELSE prod.VentaNeto    END,
               prod.PrecioLista2 = CASE WHEN pv.hasS = 1 THEN pv.precioS ELSE prod.PrecioLista2 END,
               prod.last_update  = CONVERT(date, SYSUTCDATETIME())
        FROM dbo.producto prod
                 INNER JOIN pivoted pv
                            ON pv.Articulo COLLATE Modern_Spanish_CI_AS = prod.Articulo COLLATE Modern_Spanish_CI_AS;

        -- 4. Limpieza de la cola
        DELETE FROM [Mastersoft].[dbo].[PriceUpdateQueue]
        WHERE EventID IN (SELECT EventID FROM @eventsToProcess);

        COMMIT TRANSACTION;
    END TRY
    BEGIN CATCH
        IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;
        THROW;
    END CATCH
END;
GO


/* ============================================================================
   SECCIÓN 4 — JOBS EN [msdb]  (de create_jobs.sql + job de precios)
   ============================================================================ */
USE msdb;
GO

-- Limpieza previa
IF EXISTS (SELECT job_id FROM msdb.dbo.sysjobs WHERE name = N'Dipalza - Procesar StockUpdateQueue')
    EXEC sp_delete_job @job_name = N'Dipalza - Procesar StockUpdateQueue', @delete_unused_schedule = 1;
IF EXISTS (SELECT job_id FROM msdb.dbo.sysjobs WHERE name = N'Dipalza - Procesar MasterDataUpdateQueue')
    EXEC sp_delete_job @job_name = N'Dipalza - Procesar MasterDataUpdateQueue', @delete_unused_schedule = 1;
IF EXISTS (SELECT job_id FROM msdb.dbo.sysjobs WHERE name = N'Dipalza - Procesar PriceUpdateQueue')
    EXEC sp_delete_job @job_name = N'Dipalza - Procesar PriceUpdateQueue', @delete_unused_schedule = 1;
IF EXISTS (SELECT job_id FROM msdb.dbo.sysjobs WHERE name = N'Dipalza - Procesar ListaPrecioActivaQueue')
    EXEC sp_delete_job @job_name = N'Dipalza - Procesar ListaPrecioActivaQueue', @delete_unused_schedule = 1;
GO

-- JOB 1: Stock (cada 15 segundos)
EXEC sp_add_job
     @job_name = N'Dipalza - Procesar StockUpdateQueue',
     @enabled = 1,
     @description = N'Procesa la cola de actualizaciones de stock hacia dbo.producto';
EXEC sp_add_jobstep
     @job_name = N'Dipalza - Procesar StockUpdateQueue',
     @step_name = N'Ejecutar usp_ProcessStockUpdateQueue',
     @subsystem = N'TSQL',
     @database_name = N'ventas',
     @command = N'EXEC dbo.usp_ProcessStockUpdateQueue;',
     @on_success_action = 1,
     @on_fail_action = 2;
EXEC sp_add_schedule
     @schedule_name = N'SCH_Stock_15Seg',
     @freq_type = 4, @freq_interval = 1,
     @freq_subday_type = 2, @freq_subday_interval = 15,
     @active_start_time = 0;
EXEC sp_attach_schedule
     @job_name = N'Dipalza - Procesar StockUpdateQueue',
     @schedule_name = N'SCH_Stock_15Seg';
EXEC sp_add_jobserver
     @job_name = N'Dipalza - Procesar StockUpdateQueue',
     @server_name = N'(LOCAL)';
GO

-- JOB 2: Tablas Maestras (cada 1 minuto)
EXEC sp_add_job
     @job_name = N'Dipalza - Procesar MasterDataUpdateQueue',
     @enabled = 1,
     @description = N'Procesa la cola de cambios en tablas maestras (rutas, condiciones, conducción, ila)';
EXEC sp_add_jobstep
     @job_name = N'Dipalza - Procesar MasterDataUpdateQueue',
     @step_name = N'Ejecutar usp_ProcessMasterDataQueue',
     @subsystem = N'TSQL',
     @database_name = N'ventas',
     @command = N'EXEC dbo.usp_ProcessMasterDataQueue;',
     @on_success_action = 1,
     @on_fail_action = 2;
EXEC sp_add_schedule
     @schedule_name = N'SCH_MasterData_1Min',
     @freq_type = 4, @freq_interval = 1,
     @freq_subday_type = 4, @freq_subday_interval = 1,
     @active_start_time = 0;
EXEC sp_attach_schedule
     @job_name = N'Dipalza - Procesar MasterDataUpdateQueue',
     @schedule_name = N'SCH_MasterData_1Min';
EXEC sp_add_jobserver
     @job_name = N'Dipalza - Procesar MasterDataUpdateQueue',
     @server_name = N'(LOCAL)';
GO

-- JOB 3: Precios (cada 15 segundos)
EXEC sp_add_job
     @job_name = N'Dipalza - Procesar PriceUpdateQueue',
     @enabled = 1,
     @description = N'Procesa la cola de actualizaciones de precios hacia dbo.producto (VentaNeto y PrecioLista2)';
EXEC sp_add_jobstep
     @job_name = N'Dipalza - Procesar PriceUpdateQueue',
     @step_name = N'Ejecutar usp_ProcessPriceUpdateQueue',
     @subsystem = N'TSQL',
     @database_name = N'ventas',
     @command = N'EXEC dbo.usp_ProcessPriceUpdateQueue;',
     @on_success_action = 1,
     @on_fail_action = 2;
EXEC sp_add_schedule
     @schedule_name = N'SCH_Price_15Seg',
     @freq_type = 4, @freq_interval = 1,
     @freq_subday_type = 2, @freq_subday_interval = 15,
     @active_start_time = 0;
EXEC sp_attach_schedule
     @job_name = N'Dipalza - Procesar PriceUpdateQueue',
     @schedule_name = N'SCH_Price_15Seg';
EXEC sp_add_jobserver
     @job_name = N'Dipalza - Procesar PriceUpdateQueue',
     @server_name = N'(LOCAL)';
GO

-- JOB 4: Listas activas ventas -> Mastersoft (cada 10 segundos, corre en Mastersoft)
EXEC sp_add_job
     @job_name = N'Dipalza - Procesar ListaPrecioActivaQueue',
     @enabled = 1,
     @description = N'Sincroniza ventas.ListaPrecioActiva -> Mastersoft.ListaPrecioActiva (espejo)';
EXEC sp_add_jobstep
     @job_name = N'Dipalza - Procesar ListaPrecioActivaQueue',
     @step_name = N'Ejecutar usp_ProcessListaPrecioActivaQueue',
     @subsystem = N'TSQL',
     @database_name = N'Mastersoft',
     @command = N'EXEC dbo.usp_ProcessListaPrecioActivaQueue;',
     @on_success_action = 1,
     @on_fail_action = 2;
EXEC sp_add_schedule
     @schedule_name = N'SCH_ListaActiva_10Seg',
     @freq_type = 4, @freq_interval = 1,
     @freq_subday_type = 2, @freq_subday_interval = 10,
     @active_start_time = 0;
EXEC sp_attach_schedule
     @job_name = N'Dipalza - Procesar ListaPrecioActivaQueue',
     @schedule_name = N'SCH_ListaActiva_10Seg';
EXEC sp_add_jobserver
     @job_name = N'Dipalza - Procesar ListaPrecioActivaQueue',
     @server_name = N'(LOCAL)';
GO


/* ============================================================================
   SECCIÓN 5 — CONFIGURACIÓN INICIAL Y VERIFICACIÓN
   ============================================================================ */

/* ---- Configuración inicial de listas de precio (OBLIGATORIA) --------------
   La instalación deja ListaPrecioActiva VACÍA. Sembrar en VENTAS (la fuente);
   el job de 10 s la propaga a Mastersoft y el resync re-cotiza producto.
   El guard exige la lista principal 'P'. Reemplaza los códigos por los reales.

USE ventas;
INSERT INTO dbo.ListaPrecioActiva (Rol, CodigoLista) VALUES ('P', '001');   -- principal -> VentaNeto
-- INSERT INTO dbo.ListaPrecioActiva (Rol, CodigoLista) VALUES ('S', '002'); -- secundaria -> PrecioLista2
*/

/* ---- Verificación (de are_jobs_running.sql; ejecutar a mano) --------------

-- Qué procedimiento ejecuta cada job
SELECT j.name AS JobName, s.step_id AS StepOrder, s.step_name AS StepName,
       s.command AS ExecutedCode, s.database_name AS TargetDatabase
FROM msdb.dbo.sysjobs j
         INNER JOIN msdb.dbo.sysjobsteps s ON j.job_id = s.job_id
WHERE j.name IN ('Dipalza - Procesar StockUpdateQueue',
                 'Dipalza - Procesar MasterDataUpdateQueue',
                 'Dipalza - Procesar PriceUpdateQueue',
                 'Dipalza - Procesar ListaPrecioActivaQueue')
ORDER BY j.name, s.step_id;

-- ¿Existen los procedimientos? (ejecutar en [ventas])
SELECT name, type_desc, create_date, modify_date
FROM sys.objects
WHERE object_id IN (OBJECT_ID('usp_ProcessStockUpdateQueue'),
                    OBJECT_ID('usp_ProcessMasterDataQueue'),
                    OBJECT_ID('usp_ProcessPriceUpdateQueue'))
  AND type IN ('P', 'PC');

-- Último estado de cada job
SELECT j.name AS JobName, j.enabled AS Enabled,
       CASE h.run_status
           WHEN 0 THEN 'Failed' WHEN 1 THEN 'Succeeded' WHEN 2 THEN 'Retry'
           WHEN 3 THEN 'Cancelled' WHEN 4 THEN 'Running' END AS LastRunStatus,
       msdb.dbo.agent_datetime(h.run_date, h.run_time) AS LastRunTime,
       h.run_duration AS DurationHHMMSS, h.message AS Message
FROM msdb.dbo.sysjobs j
         LEFT JOIN msdb.dbo.sysjobhistory h
                   ON h.job_id = j.job_id
                       AND h.instance_id = (SELECT MAX(instance_id)
                                            FROM msdb.dbo.sysjobhistory
                                            WHERE job_id = j.job_id)
ORDER BY j.name;

   ============================================================================ */
