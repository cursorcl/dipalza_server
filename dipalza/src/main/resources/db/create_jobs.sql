USE msdb;
GO

-- =============================================
-- JOB 1: Procesar cola de Stock
-- =============================================
EXEC sp_add_job
    @job_name = N'Dipalza - Procesar StockUpdateQueue',
    @enabled = 1,
    @description = N'Procesa la cola de actualizaciones de stock hacia dbo.producto';

EXEC sp_add_jobstep
    @job_name = N'Dipalza - Procesar StockUpdateQueue',
    @step_name = N'Ejecutar usp_ProcessStockUpdateQueue',
    @subsystem = N'TSQL',
    @database_name = N'ventas',   -- BD donde vive el SP destino
    @command = N'EXEC dbo.usp_ProcessStockUpdateQueue;',
    @on_success_action = 1,       -- Quit with success
    @on_fail_action = 2;          -- Quit with failure

EXEC sp_add_schedule
    @schedule_name = N'Cada 1 minuto - Stock',
    @freq_type = 4,               -- Daily
    @freq_interval = 1,
    @freq_subday_type = 4,        -- Minutes
    @freq_subday_interval = 1,    -- Cada 1 minuto
    @active_start_time = 0;

EXEC sp_attach_schedule
    @job_name = N'Dipalza - Procesar StockUpdateQueue',
    @schedule_name = N'Cada 1 minuto - Stock';

EXEC sp_add_jobserver
    @job_name = N'Dipalza - Procesar StockUpdateQueue',
    @server_name = N'(LOCAL)';

GO

-- =============================================
-- JOB 2: Procesar cola de Tablas Maestras
-- =============================================
EXEC sp_add_job
    @job_name = N'Dipalza - Procesar MasterDataUpdateQueue',
    @enabled = 1,
    @description = N'Procesa la cola de cambios en tablas maestras (rutas, condiciones, conducción)';

EXEC sp_add_jobstep
    @job_name = N'Dipalza - Procesar MasterDataUpdateQueue',
    @step_name = N'Ejecutar usp_ProcessMasterDataQueue',
    @subsystem = N'TSQL',
    @database_name = N'ventas',   -- BD donde vive el SP destino
    @command = N'EXEC dbo.usp_ProcessMasterDataQueue;',
    @on_success_action = 1,
    @on_fail_action = 2;

EXEC sp_add_schedule
    @schedule_name = N'Cada 5 minutos - MasterData',
    @freq_type = 4,
    @freq_interval = 1,
    @freq_subday_type = 4,        -- Minutes
    @freq_subday_interval = 5,    -- Cada 5 minutos
    @active_start_time = 0;

EXEC sp_attach_schedule
    @job_name = N'Dipalza - Procesar MasterDataUpdateQueue',
    @schedule_name = N'Cada 5 minutos - MasterData';

EXEC sp_add_jobserver
    @job_name = N'Dipalza - Procesar MasterDataUpdateQueue',
    @server_name = N'(LOCAL)';

GO