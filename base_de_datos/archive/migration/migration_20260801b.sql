-- Agrega la columna paradaVendedorId a dbo.parada_vendedor_grupo_actual:
-- enlaza el grupo en curso con la fila de dbo.parada_vendedor que ya se
-- creo para el (una vez que la duracion acumulada cruzo el umbral de 10
-- min), para poder ACTUALIZARLA en cada extension posterior del grupo en
-- vez de esperar a que el grupo se cierre. NULL mientras el grupo no ha
-- calificado todavia.
--
-- Ver DeteccionParadaService.extenderGrupo().

BEGIN TRAN;

ALTER TABLE dbo.parada_vendedor_grupo_actual
    ADD paradaVendedorId bigint NULL;

COMMIT TRAN;
