-- Saber qué procedimiento almacenado está ejecutando cada job
SELECT
    j.name AS JobName,
    s.step_id AS StepOrder,
    s.step_name AS StepName,
    s.command AS ExecutedCode, -- Aquí verá el EXEC o script T-SQL
    s.database_name AS TargetDatabase
FROM msdb.dbo.sysjobs j
         INNER JOIN msdb.dbo.sysjobsteps s ON j.job_id = s.job_id
WHERE j.name IN ('Dipalza - Procesar MasterDataUpdateQueue', 'Dipalza - Procesar StockUpdateQueue')
ORDER BY j.name, s.step_id;


-- Saber si el procedimiento almacenado existe
SELECT
    name,
    type_desc,
    create_date,
    modify_date
FROM sys.objects
WHERE (object_id = OBJECT_ID('usp_ProcessStockUpdateQueue;') or  object_id = OBJECT_ID('usp_ProcessMasterDataQueue;'))
  AND type IN ('P', 'PC');

-- Ver todos los jobs y su último estado
SELECT
    j.name AS JobName,
    j.enabled AS Enabled,
    CASE h.run_status
        WHEN 0 THEN 'Failed'
        WHEN 1 THEN 'Succeeded'
        WHEN 2 THEN 'Retry'
        WHEN 3 THEN 'Cancelled'
        WHEN 4 THEN 'Running'
        END AS LastRunStatus,
    msdb.dbo.agent_datetime(h.run_date, h.run_time) AS LastRunTime,
    h.run_duration AS DurationHHMMSS,
    h.message AS Message
FROM msdb.dbo.sysjobs j
         LEFT JOIN msdb.dbo.sysjobhistory h
                   ON h.job_id = j.job_id
                       AND h.instance_id = (
                           SELECT MAX(instance_id)
                           FROM msdb.dbo.sysjobhistory
                           WHERE job_id = j.job_id
                       )
ORDER BY j.name;