-- Retira la columna `posicion geography` (y su indice espacial) de dbo.posicion
-- y dbo.historial_posicion. No se preserva el dato existente en la columna
-- geography: dbo.posicion es estado transitorio que se repuebla con el proximo
-- reporte GPS de cada vendedor. dbo.historial_posicion ya cuenta con las
-- columnas latitud/longitud, por lo que solo se retira la columna geography
-- sobrante y su indice espacial.
--
-- IMPORTANTE: dbo.posicion y dbo.historial_posicion tenian ademas dos
-- triggers no versionados (trg_ActualizarPosicionGeo / trg_ActualizarHistorial
-- PosicionGeo), creados directo en el servidor de BD, fuera de este repo
-- (ver base_de_datos/db/diseno_sincronizacion_dipalza.md). Recalculaban la
-- columna `posicion` desde latitud/longitud en cada INSERT/UPDATE. Al
-- quitarle la columna a dbo.posicion, el trigger de esa tabla queda roto
-- (referencia una columna inexistente) y bloquea CUALQUIER insert/update
-- futuro con el error "Invalid column name 'posicion'". Se eliminan ambos
-- triggers al final de este script: ya no tienen ningun proposito una vez
-- que la app trabaja 100% con latitud/longitud planos.

BEGIN TRAN;

-- ---- dbo.posicion ----------------------------------------------------------
ALTER TABLE dbo.posicion
    ADD latitud  float NOT NULL CONSTRAINT DF_posicion_latitud  DEFAULT 0,
        longitud float NOT NULL CONSTRAINT DF_posicion_longitud DEFAULT 0;

ALTER TABLE dbo.posicion DROP CONSTRAINT DF_posicion_latitud, DF_posicion_longitud;

ALTER TABLE dbo.posicion DROP COLUMN posicion;

-- ---- dbo.historial_posicion -------------------------------------------------
DROP INDEX sidx_historial_posicion ON dbo.historial_posicion;

ALTER TABLE dbo.historial_posicion DROP COLUMN posicion;

-- ---- Triggers no versionados, ya sin proposito -----------------------------
IF OBJECT_ID('dbo.trg_ActualizarPosicionGeo', 'TR') IS NOT NULL
    DROP TRIGGER dbo.trg_ActualizarPosicionGeo;

IF OBJECT_ID('dbo.trg_ActualizarHistorialPosicionGeo', 'TR') IS NOT NULL
    DROP TRIGGER dbo.trg_ActualizarHistorialPosicionGeo;

COMMIT TRAN;
