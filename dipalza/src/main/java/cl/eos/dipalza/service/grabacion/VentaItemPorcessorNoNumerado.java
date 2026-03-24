package cl.eos.dipalza.service.grabacion;

import cl.eos.dipalza.entity.Producto;
import cl.eos.dipalza.repository.ProductoRepository;
import cl.eos.dipalza.service.resultados.VentaItemResultado;
import cl.eos.dipalza.utils.Constants;
import lombok.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;

@Component
public class VentaItemPorcessorNoNumerado implements VentaItemProcessor {

    private final PlatformTransactionManager transactionManager;
    private final JdbcTemplate jdbcFacturacionTemplate;
    private final ProductoRepository productoRepository;
    public VentaItemPorcessorNoNumerado(@Qualifier("facturacionTransactionManager") PlatformTransactionManager transactionManager,
                                        @Qualifier("facturacionJdbcTemplate") JdbcTemplate jdbcFacturacionTemplate,
                                        ProductoRepository productoRepository) {
        this.transactionManager = transactionManager;
        this.jdbcFacturacionTemplate = jdbcFacturacionTemplate;
        this.productoRepository = productoRepository;
    }
    @Override
    public boolean soporta(@NonNull VentaItemContext context) {
        return !context.esNumerado();
    }

    @Override
    public VentaItemResultado procesar(@NonNull VentaItemContext context) {
        var detalle = context.detalle();
        var nroLinea = context.nroLinea();
        String numeroLinea = String.format("%03d", nroLinea);
        var idenficador = context.idenficador();

        var producto = productoRepository.findById(detalle.getIdProducto()).orElse(null);

        if(producto == null)
            return new VentaItemResultado(
                    detalle.getIdProducto(),
                    nroLinea,
                    detalle.getPrecioUnitario().floatValue(),
                    0f,
                    0f,
                    0f,
                    0f,
                    0f,
                    0f,
                    null,
                    "No existe el código de producto!");

        var cantidadPedida = detalle.getCantidad().floatValue();


        ///  Asegurando que los porcentajes esté entre 0 y 1
        var porcentajeIva = detalle.getPorcentajeIva().floatValue();
        porcentajeIva = porcentajeIva > 1 ? porcentajeIva / 100f : porcentajeIva;
        var porcentajeIla = detalle.getPorcentajeIla().floatValue();
        porcentajeIla = porcentajeIla > 1 ? porcentajeIla / 100f : porcentajeIla;
        var porcDescuento = detalle.getPorcentajeDescuento().floatValue();
        porcDescuento = porcDescuento > 1 ? porcDescuento / 100f : porcDescuento;

        /// Factor que aplico para calcular el valor con descuento aplicado.
        var factorDebidoAlDescuento = 1f - porcDescuento;

        var stockActual = producto.getStock().floatValue();
        var precioVentaNeto = producto.getVentaNeto().floatValue();

        /// Diferencia
        float diferenciaStock = stockActual - cantidadPedida;
        float cantidadAsignada = diferenciaStock < 0 ? stockActual : cantidadPedida;


        ///  Con base en la cantidad que se determina que puede vender, se calcula la venta de la línea.
        var ventaNetaReal = Math.round(precioVentaNeto * cantidadAsignada * factorDebidoAlDescuento);
        var cantidadFaltante = cantidadPedida - cantidadAsignada;

        var valorIvaDeLaVenta = ventaNetaReal * porcentajeIva;
        var valorIlaDeLaVenta = ventaNetaReal * porcentajeIla;
        var valorDescDeLaVenta = precioVentaNeto * cantidadAsignada * porcDescuento;

        try {

            int rowsAffected = jdbcFacturacionTemplate.update(
                    Constants.INSERT_DETALLE_DOCUMENTO,
                    precioVentaNeto,          // precioventa
                    ventaNetaReal,          // totallinea
                    Constants.PARIDAD,      // paridad
                    producto.getCosto().floatValue(), // costo
                    cantidadAsignada,       // cantidad
                    idenficador,            // id (Su VARCHAR manual)
                    numeroLinea,            // linea
                    Constants.TIPO_DOCUMENTO_FACTURA, // tipoid
                    Constants.LOCAL_000,    // local
                    producto.getArticulo(), // articulo
                    producto.getDescripcion(), // descripcion
                    porcDescuento * -1f     // variacion
            );

            if (rowsAffected > 0) {
                // Actualiza la cantidad vendida en cada produco
                actualizarStockProducto(producto, 0, cantidadAsignada);
            }

            return new VentaItemResultado(
                    producto.getArticulo(),
                    nroLinea,
                    precioVentaNeto,
                    ventaNetaReal,
                    cantidadAsignada,
                    cantidadFaltante,
                    valorIvaDeLaVenta,
                    valorIlaDeLaVenta,
                    valorDescDeLaVenta,
                    null,
                    rowsAffected <= 0 ? "No se ha podido grabar el registro" :  null
            );

        } catch(DataAccessException ex) {
            ex.printStackTrace();
            return new VentaItemResultado(
                    producto.getArticulo(),
                    nroLinea,
                    precioVentaNeto,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    null,
                    "No se ha podido almacenar el registro:" + ex.getCause()
            );
        }
        catch(Exception ex) {
            ex.printStackTrace();
            return new VentaItemResultado(
                    producto.getArticulo(),
                    nroLinea,
                    precioVentaNeto,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    null,
                    "No se ha podido almacenar el registro:" + ex.getCause()
            );
        }


    }

    private void actualizarStockProducto(Producto producto, float cantidadPiezasAsignada, float cantidadAsignada) {
        // Se rebaja de la cantidad de vendidos lo que ya se pasó a facturación.
        float nuevoStockVentas =  producto.getStockVentas().floatValue() - cantidadAsignada;
        float nuevoPiezasVentas = producto.getPiezasVentas().floatValue() - cantidadPiezasAsignada;
        producto.setStockVentas(new BigDecimal(nuevoStockVentas));
        producto.setPiezasVentas(new BigDecimal(nuevoPiezasVentas));
        productoRepository.save(producto);
    }
}
