USE ventas;
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
