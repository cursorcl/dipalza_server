/* ============================================================================
   07_poblado_inicial_ventas.sql
   Poblado inicial masivo de [ventas] desde [Mastersoft].
   Ejecutar UNA SOLA VEZ, después de 00-06, sobre tablas [ventas] vacías.
   NO es idempotente: correrlo dos veces duplicará filas o fallará por PK.
   ============================================================================ */
USE ventas;
GO

SET XACT_ABORT ON;
BEGIN TRY
    BEGIN TRANSACTION;

    -- ---- Rutas (Mastersoft.msosttablas, tabla='017') -----------------------
    -- conduccion_id: regla de negocio confirmada por el usuario (no viene de
    -- Mastersoft, msosttablas no trae ese dato para rutas).
    INSERT INTO dbo.ruta (codigo, descripcion, conduccion_id)
    SELECT
        codigo,
        descripcion,
        CASE codigo
            WHEN '001' THEN '9999'
            WHEN '003' THEN '9998'
            ELSE '9997'
        END AS conduccion_id
    FROM Mastersoft.dbo.msosttablas
    WHERE tabla = '017';

    -- ---- Condición de venta (Mastersoft.msosttablas, tabla='009') ----------
    INSERT INTO dbo.condicionventa (codigo, descripcion, dias)
    SELECT codigo, descripcion, valor
    FROM Mastersoft.dbo.msosttablas
    WHERE tabla = '009';

    -- ---- Conducción (Mastersoft.msosttablas, tabla='015') ------------------
    INSERT INTO dbo.conduccion (codigo, descripcion, valor)
    SELECT codigo, descripcion, valor
    FROM Mastersoft.dbo.msosttablas
    WHERE tabla = '015';

    -- ---- ILA (Mastersoft.msosttablas, tabla='004') -------------------------
    INSERT INTO dbo.ila (codigo, descripcion, valor)
    SELECT codigo, descripcion, valor
    FROM Mastersoft.dbo.msosttablas
    WHERE tabla = '004';

    -- ---- Productos (Mastersoft.ARTICULO + articulosnumerados) --------------
    -- TODO: Stock=0 es temporal. Reemplazar por el resultado real de
    --       calcularStock/calcularStockNumerado (procedimientos existentes
    --       en Mastersoft) cuando se implemente esa parte.
    -- TODO: costo=0 es temporal. Origen real aún no identificado en Mastersoft.
    INSERT INTO dbo.producto (
        Articulo, Descripcion, VentaNeto, PorcIla, PorcCarne, Unidad,
        Stock, CodigoIla, last_update, numbered, stockVentas, piezasVentas, costo
    )
    SELECT
        a.Articulo,
        a.Descripcion,
        a.VentaNeto,
        a.PorcIla,
        a.PorcCarne,
        a.Unidad,
        0 AS Stock,                                                   -- TODO: calcularStock/calcularStockNumerado
        a.CodigoIla,
        CONVERT(date, SYSUTCDATETIME()),
        CASE WHEN an.Articulo IS NOT NULL THEN 1 ELSE 0 END AS numbered,
        0 AS stockVentas,
        0 AS piezasVentas,
        0 AS costo                                                     -- TODO: origen real no identificado
    FROM Mastersoft.dbo.ARTICULO a
    LEFT JOIN Mastersoft.dbo.articulosnumerados an
        ON an.Articulo = a.Articulo COLLATE Modern_Spanish_CI_AS;

    -- ---- Precio secundario (rol 'S'), solo si Mastersoft.ListaPrecioActiva
    --      ya tiene una lista secundaria activa configurada -----------------
    UPDATE p
       SET p.PrecioLista2 = pr.VentaNeto
    FROM dbo.producto p
    INNER JOIN Mastersoft.dbo.PRECIOS pr
        ON pr.Articulo COLLATE Modern_Spanish_CI_AS = p.Articulo COLLATE Modern_Spanish_CI_AS
    INNER JOIN Mastersoft.dbo.ListaPrecioActiva la
        ON la.CodigoLista COLLATE Modern_Spanish_CI_AS = pr.CodigoLista COLLATE Modern_Spanish_CI_AS
       AND la.Rol = 'S';

    -- ---- Clientes (Mastersoft.msoclientes) ----------------------------------
    -- TODO: verificar nombre real de cada columna en msoclientes; se asume
    --       el mismo nombre que la columna destino como punto de partida.
    INSERT INTO dbo.cliente (rut, codigo, razon, direccion, ciudad, giro, telefono, codigo_ruta, codigo_vendedor)
    SELECT
        rut,             -- TODO: verificar nombre real de columna en msoclientes
        codigo,          -- TODO: verificar nombre real de columna en msoclientes
        razon,           -- TODO: verificar nombre real de columna en msoclientes
        direccion,       -- TODO: verificar nombre real de columna en msoclientes
        ciudad,          -- TODO: verificar nombre real de columna en msoclientes
        giro,            -- TODO: verificar nombre real de columna en msoclientes
        telefono,        -- TODO: verificar nombre real de columna en msoclientes
        codigo_ruta,     -- TODO: verificar nombre real de columna en msoclientes
        codigo_vendedor  -- TODO: verificar nombre real de columna en msoclientes
    FROM Mastersoft.dbo.msoclientes;

    -- ---- Vendedores (Mastersoft.msovendedor) --------------------------------
    -- TODO: verificar nombre real de cada columna en msovendedor.
    INSERT INTO dbo.vendedor (rut, codigo, tipo, nombre, ciudad, comuna, direccion, telefono)
    SELECT
        rut,        -- TODO: verificar nombre real de columna en msovendedor
        codigo,     -- TODO: verificar nombre real de columna en msovendedor
        tipo,       -- TODO: verificar nombre real de columna en msovendedor
        nombre,     -- TODO: verificar nombre real de columna en msovendedor
        ciudad,     -- TODO: verificar nombre real de columna en msovendedor
        comuna,     -- TODO: verificar nombre real de columna en msovendedor
        direccion,  -- TODO: verificar nombre real de columna en msovendedor
        telefono    -- TODO: verificar nombre real de columna en msovendedor
    FROM Mastersoft.dbo.msovendedor;

    -- vendedor_ruta: NO se puebla (confirmado). La asignación de rutas a
    -- vendedores se gestiona manualmente/por la app después de esta carga.

    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;
    THROW;
END CATCH
GO
