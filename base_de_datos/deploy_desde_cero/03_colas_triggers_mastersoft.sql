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
