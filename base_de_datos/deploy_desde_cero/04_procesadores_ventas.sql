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
                WHEN MATCHED AND S.ChangeType IN ('I','U') AND (T.descripcion <> S.descripcion COLLATE Modern_Spanish_CI_AS OR T.dias <> S.valor)
                    THEN UPDATE SET T.descripcion = S.descripcion, T.dias = S.valor
                WHEN MATCHED AND S.ChangeType = 'D' THEN DELETE
                WHEN NOT MATCHED BY TARGET AND S.ChangeType IN ('I','U')
                    THEN INSERT (codigo, descripcion, dias) VALUES (S.codigo, S.descripcion, S.valor);

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
