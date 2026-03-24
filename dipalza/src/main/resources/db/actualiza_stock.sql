-- Tabla para el update del stock de productos en Mastersoft
CREATE TABLE dbo.StockUpdateQueue
(
    EventID     BIGINT IDENTITY (1,1) PRIMARY KEY,
    Articulo    NVARCHAR(50)   NOT NULL, -- O el tipo de dato que uses para el código de artículo
    DeltaStock  DECIMAL(18, 4) NOT NULL,
    CreatedAt   DATETIME2(7) DEFAULT SYSUTCDATETIME(),
    ProcessedAt DATETIME2(7)   NULL      -- Se marcará cuando el evento sea procesado
);

-- Procedimiento almacenado en ventas para procesar los cambios en la cola de stock
CREATE OR ALTER PROCEDURE dbo.usp_ProcessStockUpdateQueue
AS
BEGIN
    SET NOCOUNT ON;

    DECLARE @batchSize INT = 500;

    -- Variable de tabla para capturar los IDs específicos que vamos a procesar
    DECLARE @eventsToProcess TABLE (
                                       EventID BIGINT PRIMARY KEY,
                                       Articulo NVARCHAR(50),
                                       DeltaStock DECIMAL(18, 4)
                                   );

    -- Paso A: Capturar el lote de eventos y marcarlos como procesados
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

    -- Si no hay eventos, salimos inmediatamente
    IF NOT EXISTS (SELECT 1 FROM @eventsToProcess)
        BEGIN
            RETURN;
        END

        -- Paso B & C: Agrupar deltas y aplicar MERGE sobre la tabla 'producto'
        ;WITH agg_batch AS (
        SELECT Articulo, SUM(DeltaStock) AS FinalDelta
        FROM @eventsToProcess
        GROUP BY Articulo
        ),
        SourceData AS (
            SELECT
                ab.Articulo,
                ab.FinalDelta,
                a.Descripcion,
                a.VentaNeto,
                a.PorcIla,
                a.PorcCarne,
                a.Unidad,
                a.CodigoIla,
                CASE WHEN an.Articulo IS NOT NULL THEN 1 ELSE 0 END AS EsNumerado
            FROM agg_batch ab
                     -- Unión con tablas maestras para nuevos productos
                     LEFT JOIN [Mastersoft].[dbo].[ARTICULO] a
                               ON a.Articulo = ab.Articulo COLLATE DATABASE_DEFAULT
                     LEFT JOIN [Mastersoft].[dbo].[articulosnumerados] an
                               ON an.Articulo = ab.Articulo COLLATE DATABASE_DEFAULT
            )
        MERGE dbo.producto AS T
    USING SourceData AS S
    ON T.Articulo = S.Articulo COLLATE DATABASE_DEFAULT

        -- CASO 1: El producto YA EXISTE - Solo actualizamos stock y fecha
    WHEN MATCHED THEN
        UPDATE SET
                   T.Stock = COALESCE(T.Stock, 0) + S.FinalDelta,
                   T.last_update = SYSUTCDATETIME()

        -- CASO 2: El producto NO EXISTE - Insertamos con datos maestros
    WHEN NOT MATCHED BY TARGET AND S.Descripcion IS NOT NULL THEN
        INSERT (Articulo, Descripcion, VentaNeto, PorcIla, PorcCarne, Unidad,
                Stock, last_update, Numbered, CodigoIla)
        VALUES (S.Articulo, S.Descripcion, S.VentaNeto, S.PorcIla, S.PorcCarne, S.Unidad,
                S.FinalDelta, SYSUTCDATETIME(), S.EsNumerado, S.CodigoIla);

    -- Paso D: Limpiar físicamente la cola de los eventos procesados
    DELETE FROM [Mastersoft].[dbo].[StockUpdateQueue]
    WHERE EventID IN (SELECT EventID FROM @eventsToProcess);

END;
-- Un índice es crucial para que el proceso de lectura sea rápido
CREATE INDEX IX_StockUpdateQueue_ForProcessing ON dbo.StockUpdateQueue (ProcessedAt, CreatedAt);

-- triggers para actualizar la tabla de stock

CREATE OR ALTER TRIGGER dbo.trg_invdetallepartes_stockresumen
    ON dbo.invdetallepartes
    AFTER INSERT, UPDATE, DELETE
    AS
BEGIN
    SET NOCOUNT ON;
    ;
    WITH base AS (SELECT i.articulo, i.[Local] AS [local], i.Tipoid, i.cantidad AS cant
                  FROM inserted i
                  UNION ALL
                  SELECT d.articulo, d.[Local] AS [local], d.Tipoid, -d.cantidad AS cant
                  FROM deleted d),
         agg AS (SELECT articulo,
                        SUM(CASE
                                WHEN Tipoid = 17 THEN cant -- Entrada
                                WHEN Tipoid = 18 THEN -cant -- Salida
                                ELSE 0 END) AS delta_stock
                 FROM base
                 WHERE [local] = '000'
                 GROUP BY articulo)
    -- Referencia explícita a la tabla de la cola en Mastersoft
    INSERT
    INTO [Mastersoft].[dbo].[StockUpdateQueue] (Articulo, DeltaStock)
    SELECT articulo, delta_stock
    FROM agg
    WHERE delta_stock <> 0;
END;


CREATE OR ALTER TRIGGER dbo.trg_detalledocumento_stockresumen
    ON dbo.detalledocumento
    AFTER INSERT, UPDATE, DELETE
    AS
BEGIN
    SET NOCOUNT ON;
    ;
    WITH base AS (SELECT d.articulo, d.[local], d.tipoid, d.cantidad AS cant, e.vigente
                  FROM inserted d
                           JOIN dbo.encabezadocumento e ON e.id = d.id
                  UNION ALL
                  SELECT d.articulo, d.[local], d.tipoid, -d.cantidad AS cant, e.vigente
                  FROM deleted d
                           JOIN dbo.encabezadocumento e ON e.id = d.id),
         agg AS (SELECT articulo,
                        SUM(CASE
                                WHEN vigente = 1 AND tipoid = '09' THEN cant -- Entrada
                                WHEN vigente = 1 AND tipoid IN ('06', '10') THEN -cant -- Salida
                                ELSE 0
                            END) AS delta_stock
                 FROM base
                 WHERE [local] = '000'
                 GROUP BY articulo)
    -- Referencia explícita a la tabla de la cola en Mastersoft
    INSERT
    INTO [Mastersoft].[dbo].[StockUpdateQueue] (Articulo, DeltaStock)
    SELECT articulo, delta_stock
    FROM agg
    WHERE delta_stock <> 0;
END;


    CREATE OR ALTER TRIGGER dbo.trg_encabezadocumento_stockresumen_vigente
        ON dbo.encabezadocumento
        AFTER UPDATE
        AS
    BEGIN
        SET NOCOUNT ON;
        IF NOT UPDATE(vigente) RETURN; -- Optimización: solo actuar si cambió la vigencia

        ;WITH chg AS (
            SELECT  i.id,
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
                  SELECT  articulo,
                          SUM(CASE
                              -- Activación: aplicar flujo normal
                                  WHEN vigente_old = 0 AND vigente_new = 1 THEN
                                      CASE WHEN tipoid = '09' THEN cantidad WHEN tipoid IN ('06','10') THEN -cantidad ELSE 0 END
                              -- Desactivación: revertir flujo
                                  WHEN vigente_old = 1 AND vigente_new = 0 THEN
                                      CASE WHEN tipoid = '09' THEN -cantidad WHEN tipoid IN ('06','10') THEN cantidad ELSE 0 END
                                  ELSE 0
                              END) AS delta_stock
                  FROM det
                  GROUP BY articulo
              )
         -- Referencia explícita a la tabla de la cola en Mastersoft
         INSERT INTO [Mastersoft].[dbo].[StockUpdateQueue] (Articulo, DeltaStock)
         SELECT articulo, delta_stock
         FROM agg
         WHERE delta_stock <> 0;
    END;