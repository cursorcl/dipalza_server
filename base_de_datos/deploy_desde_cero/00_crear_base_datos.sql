/* ============================================================================
   DEPLOY DESDE CERO — Sincronización Mastersoft <-> ventas (Dipalza)
   ----------------------------------------------------------------------------
   Paquete de 9 scripts ejecutables EN ORDEN sobre una instancia SQL Server
   donde ya existe [Mastersoft] pero [ventas] todavía no existe.

   TOPOLOGÍA
     [Mastersoft] = base del ERP. Ya contiene las tablas operacionales/maestras
                    (invdetallepartes, detalledocumento, encabezadocumento,
                     msosttablas, ARTICULO, articulosnumerados, PRECIOS,
                     msoclientes, msovendedor, ...). Aquí se crean las COLAS,
                    la config de listas y los TRIGGERS.
     [ventas]     = base de la app Dipalza (la crea este mismo script 00).
                    Aquí se crea TODO el esquema de la app y los
                    PROCEDIMIENTOS que consumen las colas.
     [msdb]       = SQL Server Agent. Aquí se crean los JOBS (05, deshabilitados)
                    y se habilitan (08).

   CRUCES DE BASE: todas las bases están en la misma instancia -> las
   transacciones cross-database son locales, no requieren MSDTC.

   PRERREQUISITOS
     - [Mastersoft] ya existe, con sus tablas ERP y SQL Server Agent activo.
     - [ventas] NO debe existir todavía (este script la crea).

   ORDEN DE EJECUCIÓN (archivos de esta carpeta)
     00_crear_base_datos.sql          -> crea [ventas] + login dipalza_app (con acceso a [ventas] y [Mastersoft])
     01_esquema_ventas.sql            -> esquema completo + seed de roles
     02_listaprecioactiva_fuente.sql  -> ListaPrecioActiva fuente + triggers en [ventas]
     03_colas_triggers_mastersoft.sql -> colas + triggers + procesador inverso en [Mastersoft]
     04_procesadores_ventas.sql       -> los 3 procesadores de sincronización en [ventas]
     05_jobs_msdb.sql                 -> los 4 jobs del Agent en [msdb] (creados @enabled = 0)
     06_configuracion_inicial.sql     -> instrucciones manuales (ListaPrecioActiva + verificación)
     07_poblado_inicial_ventas.sql    -> carga masiva inicial desde [Mastersoft]
     08_habilitar_jobs.sql            -> habilita los 4 jobs recién después de que 07 y el
                                          seed manual de 06 estén confirmados (evita condición
                                          de carrera entre los jobs y la carga masiva de 07)

   Extraído y adaptado de base_de_datos/db/install_dipalza_sync.sql, que
   permanece intacto como referencia histórica (ver docs/superpowers/specs/
   2026-07-19-deploy-desde-cero-ventas-design.md para el detalle completo).
   ============================================================================ */

/* ============================================================================
   00_crear_base_datos.sql
   Crea la base de datos [ventas] desde cero. Ejecutar antes que cualquier
   otro script de esta carpeta. Requiere que [Mastersoft] ya exista en la
   misma instancia (no se crea aquí).
   ============================================================================ */
USE master;
GO

IF DB_ID(N'ventas') IS NOT NULL
BEGIN
    RAISERROR (N'La base de datos [ventas] ya existe. Este script asume una instalación desde cero; abortando para no pisar una base existente.', 16, 1);
END
GO

CREATE DATABASE ventas
    COLLATE Modern_Spanish_CI_AS;
GO

/* ---- AUTO_CLOSE de Mastersoft --------------------------------------------
   Si [Mastersoft] se restauró desde un .bak de una instancia SQL Server
   Express, hereda AUTO_CLOSE=ON (default de fábrica de Express). Con eso
   activado, el job "Dipalza - Procesar ListaPrecioActivaQueue" (corre en
   [Mastersoft], ver 05_jobs_msdb.sql) falla intermitentemente con "The
   last step to run was step 0 (no steps ran)" — confirmado el 2026-08-09,
   incluso invocando el job manualmente vía sp_start_job; desaparece por
   completo al desactivar AUTO_CLOSE. No es un problema del intervalo del
   schedule ni del procedimiento en sí. Se desactiva acá, antes de crear
   ningún job (05). ------------------------------------------------------- */
ALTER DATABASE Mastersoft SET AUTO_CLOSE OFF;
GO

/* ---- Login dedicado de la app (en vez de sa) --------------------------
   Reemplaza CAMBIAR_ESTA_CLAVE por una clave real antes de ejecutar, y
   configura esa misma clave como DB_PASSWORD / FACTURACION_DB_PASSWORD
   en el servidor de la app (mismo patrón que JWT_SECRET: se setea directo
   en el servidor, no vía GitHub Actions). ------------------------------- */
USE master;
GO

IF EXISTS (SELECT 1 FROM sys.server_principals WHERE name = N'dipalza_app')
BEGIN
    RAISERROR (N'El login [dipalza_app] ya existe. Este script asume una instalación desde cero; abortando para no pisar un login existente.', 16, 1);
END
GO

CREATE LOGIN dipalza_app
    WITH PASSWORD = N'CAMBIAR_ESTA_CLAVE',
    CHECK_POLICY = ON,
    CHECK_EXPIRATION = OFF;
GO

USE ventas;
GO

CREATE USER dipalza_app FOR LOGIN dipalza_app;
GO

ALTER ROLE db_datareader ADD MEMBER dipalza_app;
ALTER ROLE db_datawriter ADD MEMBER dipalza_app;
GRANT CONNECT TO dipalza_app;
GO

USE Mastersoft;
GO

CREATE USER dipalza_app FOR LOGIN dipalza_app;
GO

ALTER ROLE db_datareader ADD MEMBER dipalza_app;
ALTER ROLE db_datawriter ADD MEMBER dipalza_app;
GRANT CONNECT TO dipalza_app;
GO
