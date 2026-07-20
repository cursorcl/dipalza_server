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
