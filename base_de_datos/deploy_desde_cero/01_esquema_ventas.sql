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
    costo        money NOT NULL DEFAULT 0,          -- [DRIFT] existe en producción pero no estaba en install_dipalza_sync.sql; requerido por la entidad JPA Producto
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
