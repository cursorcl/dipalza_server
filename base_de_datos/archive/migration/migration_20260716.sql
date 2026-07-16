-- Agrega tabla de asociación vendedor-rutas (rutas que cubre cada vendedor)
CREATE TABLE dbo.vendedor_ruta (
    codigo_vendedor varchar(3)  COLLATE Modern_Spanish_CI_AS NOT NULL,
    tipo_vendedor   varchar(1)  COLLATE Modern_Spanish_CI_AS NOT NULL,
    codigo_ruta     varchar(10) COLLATE Modern_Spanish_CI_AS NOT NULL,
    CONSTRAINT PK_vendedor_ruta PRIMARY KEY (codigo_vendedor, tipo_vendedor, codigo_ruta),
    CONSTRAINT FK_vendedor_ruta_vendedor FOREIGN KEY (codigo_vendedor, tipo_vendedor) REFERENCES dbo.vendedor(codigo, tipo),
    CONSTRAINT FK_vendedor_ruta_ruta FOREIGN KEY (codigo_ruta) REFERENCES dbo.ruta(codigo)
);
