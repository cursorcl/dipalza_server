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

    -- ---- Configuración (copia exacta de lo que hay hoy en [ventas] de
    --      producción, confirmado por el usuario el 2026-08-09; no existe
    --      fuente equivalente en Mastersoft, son parámetros propios de la
    --      app). Si estos valores cambian en producción, actualizar aquí. --
    INSERT INTO dbo.configuracion (propiedad, valor, tipo, descripcion) VALUES
        ('FACTURA_ELECTRONICA', 'true', 'BOOLEAN', 'Se usa o no factura electrónica'),
        ('NUMERO_LINEAS_FACTURA', '25', 'INTEGER', 'Número de líneas en una factura');

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
        -- VentaNeto inicial es provisorio (viene de Mastersoft.ARTICULO, no de la
        -- lista de precios activa); se corrige tras el seed de 06 y el job de precios habilitado en 08.
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
                                                                        -- (PrecioLista2 no va aquí: se completa más abajo desde Mastersoft.PRECIOS)
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

    -- ---- Numerados (Mastersoft.numerados) -----------------------------------
    -- Mapeo confirmado por el usuario el 2026-08-09: .articulo (varchar) es el
    -- que calza con producto.Articulo (igual que usa articulosnumerados más
    -- arriba); .narticulo es otro código interno de Mastersoft, no se usa acá.
    -- Mastersoft.numerados no trae 'estado', queda en el default 'D' del
    -- esquema (dbo.numerados.estado). Debe ejecutarse después de Productos
    -- por la FK numerados.articulo -> producto.Articulo. Se descartan filas
    -- con articulo vacío y filas "basura" cuyo articulo no calza con ningún
    -- producto real (confirmado con el usuario: 18 filas en la data de
    -- referencia, códigos como '.', '|', '.21', '10' — no son artículos).
    INSERT INTO dbo.numerados (articulo, numero, peso)
    SELECT n.articulo, n.numero, n.peso
    FROM Mastersoft.dbo.numerados n
    INNER JOIN dbo.producto p
        ON p.Articulo = n.articulo COLLATE Modern_Spanish_CI_AS
    WHERE LTRIM(RTRIM(n.articulo)) <> '';

    -- ---- Clientes (Mastersoft.msoclientes) ----------------------------------
    -- Nombres de columna verificados contra Mastersoft.dbo.msoclientes real:
    -- Ruta y Vendedor (no codigo_ruta/codigo_vendedor); el resto coincide 1:1.
    INSERT INTO dbo.cliente (rut, codigo, razon, direccion, ciudad, giro, telefono, codigo_ruta, codigo_vendedor)
    SELECT
        rut,
        codigo,
        razon,
        direccion,
        ciudad,
        giro,
        telefono,
        Ruta,
        Vendedor
    FROM Mastersoft.dbo.msoclientes;

    -- ---- Vendedores (Mastersoft.msovendedor) --------------------------------
    -- Nombres de columna verificados contra Mastersoft.dbo.msovendedor real: coinciden 1:1.
    INSERT INTO dbo.vendedor (rut, codigo, tipo, nombre, ciudad, comuna, direccion, telefono)
    SELECT
        rut,
        codigo,
        tipo,
        nombre,
        ciudad,
        comuna,
        direccion,
        telefono
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
