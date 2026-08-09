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
-- Se crea deshabilitado: habilitar recién después de que
-- 07_poblado_inicial_ventas.sql termine con éxito, vía 08_habilitar_jobs.sql.
EXEC sp_add_job
     @job_name = N'Dipalza - Procesar StockUpdateQueue',
     @enabled = 0,
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
-- Se crea deshabilitado: habilitar recién después de que
-- 07_poblado_inicial_ventas.sql termine con éxito, vía 08_habilitar_jobs.sql.
EXEC sp_add_job
     @job_name = N'Dipalza - Procesar MasterDataUpdateQueue',
     @enabled = 0,
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
-- Se crea deshabilitado: habilitar recién después de que
-- 07_poblado_inicial_ventas.sql termine con éxito, vía 08_habilitar_jobs.sql.
EXEC sp_add_job
     @job_name = N'Dipalza - Procesar PriceUpdateQueue',
     @enabled = 0,
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

-- JOB 4: Listas activas ventas -> Mastersoft (cada 1 minuto, corre en Mastersoft)
-- Antes cada 10 segundos: en SQL Server 2022 ese intervalo tan agresivo
-- hace que el Agent rechace la ejecución casi siempre ("no steps ran"),
-- sin que el procedimiento llegue a correr — no reproducible en SQL Server
-- 2019 (producción). Confirmado el 2026-08-09 contra una instancia 2022
-- de prueba; ver docs/superpowers/specs para el detalle. Se sube a 1
-- minuto, igual que SCH_MasterData_1Min.
-- Se crea deshabilitado: habilitar recién después de que
-- 07_poblado_inicial_ventas.sql termine con éxito, vía 08_habilitar_jobs.sql.
EXEC sp_add_job
     @job_name = N'Dipalza - Procesar ListaPrecioActivaQueue',
     @enabled = 0,
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
     @schedule_name = N'SCH_ListaActiva_1Min',
     @freq_type = 4, @freq_interval = 1,
     @freq_subday_type = 4, @freq_subday_interval = 1,
     @active_start_time = 0;
EXEC sp_attach_schedule
     @job_name = N'Dipalza - Procesar ListaPrecioActivaQueue',
     @schedule_name = N'SCH_ListaActiva_1Min';
EXEC sp_add_jobserver
     @job_name = N'Dipalza - Procesar ListaPrecioActivaQueue',
     @server_name = N'(LOCAL)';
GO
