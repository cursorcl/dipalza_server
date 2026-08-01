-- Agrega deteccion de paradas en tiempo real: tabla de paradas persistidas
-- (dbo.parada_vendedor, una fila por parada real ya cerrada y calificada,
-- con la calle resuelta via geocodificacion inversa) y tabla de estado del
-- grupo en curso por vendedor (dbo.parada_vendedor_grupo_actual), que
-- recuerda entre llamadas HTTP sucesivas el punto de referencia, hora de
-- inicio y acumuladores de promedio del grupo aun no cerrado.
--
-- Ver PosicionService.registrarUbicacion y el nuevo DeteccionParadaService.
--
-- No se hace backfill de datos historicos: la deteccion opera solo hacia
-- adelante desde el momento en que se aplique esta migracion.

BEGIN TRAN;

CREATE TABLE dbo.parada_vendedor (
    id             bigint IDENTITY(1,1) NOT NULL,
    vendedorId     varchar(3) COLLATE Modern_Spanish_CI_AS NOT NULL,
    vendedorCodigo varchar(3) COLLATE Modern_Spanish_CI_AS NOT NULL,
    latitud        float NOT NULL,
    longitud       float NOT NULL,
    horaInicio     datetime2(0) NOT NULL,
    horaFin        datetime2(0) NOT NULL,
    calle          varchar(255) COLLATE Modern_Spanish_CI_AS NOT NULL
        CONSTRAINT DF_parada_vendedor_calle DEFAULT 'Calle no disponible',
    CONSTRAINT pk_parada_vendedor PRIMARY KEY (id)
);

CREATE NONCLUSTERED INDEX idx_parada_vendedor_vendedor_horaInicio
    ON dbo.parada_vendedor (vendedorId, vendedorCodigo, horaInicio);

CREATE TABLE dbo.parada_vendedor_grupo_actual (
    vendedorId          varchar(3) COLLATE Modern_Spanish_CI_AS NOT NULL,
    vendedorCodigo      varchar(3) COLLATE Modern_Spanish_CI_AS NOT NULL,
    dia                 date NOT NULL,
    latitudReferencia   float NOT NULL,
    longitudReferencia  float NOT NULL,
    horaInicio          datetime2(0) NOT NULL,
    horaUltimoPunto     datetime2(0) NOT NULL,
    sumaLatitud         float NOT NULL,
    sumaLongitud        float NOT NULL,
    cantidadPuntos      int NOT NULL,
    CONSTRAINT pk_parada_vendedor_grupo_actual PRIMARY KEY (vendedorId, vendedorCodigo)
);

COMMIT TRAN;
