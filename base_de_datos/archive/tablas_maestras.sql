-- cola de actualización de tablas maestras en mastersoft
CREATE TABLE dbo.MasterDataUpdateQueue (
    EventID BIGINT IDENTITY(1,1) PRIMARY KEY,
    TableName NVARCHAR(128) NOT NULL,
    PrimaryKey NVARCHAR(255) NOT NULL,
    ChangeType CHAR(1) NOT NULL, -- 'I' (Insert), 'U' (Update), 'D' (Delete)
    CreatedAt DATETIME2(7) DEFAULT SYSUTCDATETIME(),
    ProcessedAt DATETIME2(7) NULL
);

-- Índice para que el "worker" pueda leer la cola eficientemente
CREATE INDEX IX_MasterDataUpdateQueue_ForProcessing ON dbo.MasterDataUpdateQueue (ProcessedAt, CreatedAt);

-- triggers en tablas maestras en mastersoft

CREATE OR ALTER TRIGGER dbo.trg_msosttablas_sync
ON dbo.msosttablas
AFTER INSERT, UPDATE, DELETE
AS
BEGIN
    SET NOCOUNT ON;

    -- Registrar INSERCIONES
    IF EXISTS (SELECT * FROM inserted) AND NOT EXISTS (SELECT * FROM deleted)
    BEGIN
        INSERT INTO dbo.MasterDataUpdateQueue (TableName, PrimaryKey, ChangeType)
        -- Concatena 'tabla' y 'codigo' para crear una clave única
        SELECT 'msosttablas', i.tabla + '|' + i.codigo, 'I'
        FROM inserted i;
    END

    -- Registrar ACTUALIZACIONES
    IF EXISTS (SELECT * FROM inserted) AND EXISTS (SELECT * FROM deleted)
    BEGIN
        INSERT INTO dbo.MasterDataUpdateQueue (TableName, PrimaryKey, ChangeType)
        SELECT 'msosttablas', i.tabla + '|' + i.codigo, 'U'
        FROM inserted i;
    END

    -- Registrar ELIMINACIONES
    IF NOT EXISTS (SELECT * FROM inserted) AND EXISTS (SELECT * FROM deleted)
    BEGIN
        INSERT INTO dbo.MasterDataUpdateQueue (TableName, PrimaryKey, ChangeType)
        SELECT 'msosttablas', d.tabla + '|' + d.codigo, 'D'
        FROM deleted d;
    END
END;


CREATE OR ALTER PROCEDURE dbo.usp_ProcessMasterDataQueue
    AS
    BEGIN
        SET NOCOUNT ON;
        DECLARE @batchSize INT = 100;

        DECLARE @eventsToProcess TABLE (
                                           EventID BIGINT PRIMARY KEY,
                                           TableName NVARCHAR(128),
                                           PrimaryKey NVARCHAR(255),
                                           ChangeType CHAR(1)
                                       );

        -- 1. Capturar lote de eventos
        ;WITH cte_batch AS (
            SELECT TOP (@batchSize) EventID, TableName, PrimaryKey, ChangeType, ProcessedAt
            FROM [Mastersoft].[dbo].[MasterDataUpdateQueue]
            WHERE ProcessedAt IS NULL
            ORDER BY CreatedAt ASC
        )
         UPDATE cte_batch SET ProcessedAt = SYSUTCDATETIME()
         OUTPUT inserted.EventID, inserted.TableName, inserted.PrimaryKey, inserted.ChangeType
             INTO @eventsToProcess;

        -- ... (Lógica para msoclientes se mantiene igual) ...

        -- 3. Procesar los eventos de msosttablas
        IF EXISTS (SELECT 1 FROM @eventsToProcess WHERE TableName = 'msosttablas')
            BEGIN
                -- Preparamos tabla temporal persistente para este lote
                IF OBJECT_ID('tempdb..#SourceData') IS NOT NULL DROP TABLE #SourceData;

                SELECT
                    ue.ChangeType,
                    ue.tabla,
                    ue.codigo,
                    s.descripcion,
                    s.valor
                INTO #SourceData
                FROM (
                         -- Deduplicamos: solo el último evento por cada PrimaryKey
                         SELECT
                             PrimaryKey,
                             ChangeType,
                             LEFT(PrimaryKey, CHARINDEX('|', PrimaryKey) - 1) AS tabla,
                             SUBSTRING(PrimaryKey, CHARINDEX('|', PrimaryKey) + 1, LEN(PrimaryKey)) AS codigo,
                             ROW_NUMBER() OVER (PARTITION BY PrimaryKey ORDER BY EventID DESC) as rn
                         FROM @eventsToProcess
                         WHERE TableName = 'msosttablas'
                     ) ue
                         LEFT JOIN [Mastersoft].[dbo].[msosttablas] s
                                   ON s.tabla = ue.tabla COLLATE DATABASE_DEFAULT
                                       AND s.codigo = ue.codigo COLLATE DATABASE_DEFAULT
                WHERE ue.rn = 1;

                -- Aplicamos MERGE a cada destino usando la tabla temporal #SourceData

                -- Rutas (017)
                MERGE dbo.ruta AS T
                USING (SELECT * FROM #SourceData WHERE tabla = '017') AS S
                ON (T.codigo = S.codigo COLLATE DATABASE_DEFAULT)
                WHEN MATCHED AND S.ChangeType IN ('I', 'U') AND (T.descripcion <> S.descripcion COLLATE DATABASE_DEFAULT)
                    THEN UPDATE SET T.descripcion = S.descripcion
                WHEN MATCHED AND S.ChangeType = 'D' THEN DELETE
                WHEN NOT MATCHED BY TARGET AND S.ChangeType IN ('I', 'U')
                    THEN INSERT (codigo, descripcion) VALUES (S.codigo, S.descripcion);

                -- Condicion Venta (009)
                MERGE dbo.condicionventa AS T
                USING (SELECT * FROM #SourceData WHERE tabla = '009') AS S
                ON (T.codigo = S.codigo COLLATE DATABASE_DEFAULT)
                WHEN MATCHED AND S.ChangeType IN ('I', 'U') AND (T.descripcion <> S.descripcion COLLATE DATABASE_DEFAULT OR T.dias <> S.valor)
                    THEN UPDATE SET T.descripcion = S.descripcion, T.dias = S.valor
                WHEN MATCHED AND S.ChangeType = 'D' THEN DELETE
                WHEN NOT MATCHED BY TARGET AND S.ChangeType IN ('I', 'U')
                    THEN INSERT (codigo, descripcion, dias) VALUES (S.codigo, S.descripcion, S.valor);

                -- Conducción (015)
                MERGE dbo.conduccion AS T
                USING (SELECT * FROM #SourceData WHERE tabla = '015') AS S
                ON (T.codigo = S.codigo COLLATE DATABASE_DEFAULT)
                WHEN MATCHED AND S.ChangeType IN ('I', 'U') AND (T.descripcion <> S.descripcion OR T.valor <> S.valor)
                    THEN UPDATE SET T.descripcion = S.descripcion, T.valor = S.valor
                WHEN MATCHED AND S.ChangeType = 'D' THEN DELETE
                WHEN NOT MATCHED BY TARGET AND S.ChangeType IN ('I', 'U')
                    THEN INSERT (codigo, descripcion, valor) VALUES (S.codigo, S.descripcion, S.valor);

                -- ILA (004)
                MERGE dbo.ila AS T
                USING (SELECT * FROM #SourceData WHERE tabla = '004') AS S
                ON (T.codigo = S.codigo COLLATE DATABASE_DEFAULT)
                WHEN MATCHED AND S.ChangeType IN ('I', 'U') AND (T.descripcion <> S.descripcion OR T.valor <> S.valor)
                    THEN UPDATE SET T.descripcion = S.descripcion, T.valor = S.valor
                WHEN MATCHED AND S.ChangeType = 'D' THEN DELETE
                WHEN NOT MATCHED BY TARGET AND S.ChangeType IN ('I', 'U')
                    THEN INSERT (codigo, descripcion, valor) VALUES (S.codigo, S.descripcion, S.valor);

                DROP TABLE #SourceData;
            END

        -- 4. Limpieza física de la cola
        DELETE FROM [Mastersoft].[dbo].[MasterDataUpdateQueue]
        WHERE EventID IN (SELECT EventID FROM @eventsToProcess);

    END;