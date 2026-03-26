package cl.eos.dipalza.service.grabacion;

import cl.eos.dipalza.entity.*;
import cl.eos.dipalza.model.venta.VentaDetalleDTO;
import cl.eos.dipalza.repository.NumeradoRepository;
import cl.eos.dipalza.repository.ProductoRepository;
import cl.eos.dipalza.repository.VentaDetallePiezaRepository;
import cl.eos.dipalza.repository.VentaDetalleRepository;
import cl.eos.dipalza.service.resultados.NumeracionResultado;
import cl.eos.dipalza.service.resultados.VentaItemResultado;
import cl.eos.dipalza.utils.Constants;
import lombok.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class VentaItemProcessorNumerado implements VentaItemProcessor {
    private final JdbcTemplate jdbcFacturacionTemplate;
    private final ProductoRepository productoRepository;
    private final NumeradoRepository numeradoRepository;
    private final VentaDetalleRepository ventaDetalleRepository;
    private final VentaDetallePiezaRepository ventaDetallePiezaRepository;

    public VentaItemProcessorNumerado(
            @Qualifier("facturacionJdbcTemplate") JdbcTemplate jdbcFacturacionTemplate,
            ProductoRepository productoRepository,
            VentaDetalleRepository ventaDetalleRepository,
            VentaDetallePiezaRepository ventaDetallePiezaRepository,
            NumeradoRepository numeradoRepository) {
        this.jdbcFacturacionTemplate = jdbcFacturacionTemplate;
        this.productoRepository = productoRepository;
        this.numeradoRepository = numeradoRepository;
        this.ventaDetalleRepository = ventaDetalleRepository;
        this.ventaDetallePiezaRepository = ventaDetallePiezaRepository;
    }

    @Override
    public boolean soporta(@NonNull VentaItemContext context) {
        return context.esNumerado();
    }

    @Override
    public VentaItemResultado procesar(@NonNull VentaItemContext context) {
        VentaDetalleDTO detalle = context.detalle();
        int nroLinea = context.nroLinea();

        Optional<Producto> productoOpt = buscarProducto(detalle.getIdProducto());
        if(productoOpt.isEmpty()) {
            return crearResultadoErrorProductoNoExiste(detalle, nroLinea);
        }

        Producto producto = productoOpt.get();

        List<Numerado> numeradosDisponibles = buscarNumeradosDisponibles(detalle.getIdProducto());
        if(numeradosDisponibles.isEmpty()) {
            return crearResultadoErrorSinNumerados(detalle, nroLinea, producto);
        }

        Porcentajes porcentajes = extraerPorcentajes(detalle);
        AsignacionPiezas asignacion = calcularAsignacion(producto, detalle);

        if(asignacion.cantidadPiezasAsignada() <= 0) {
            return crearResultadoErrorSinStockAsignable(detalle, nroLinea, producto);
        }

        NumeradosAsignados numeradosAsignados = asignarNumerados( producto, numeradosDisponibles, asignacion.cantidadPiezasAsignada() );

        MontosVenta montos = calcularMontos( producto, numeradosAsignados.pesoRealAsignado(), porcentajes );

        try {
            guardarDetalleDocumento( context, producto, numeradosAsignados, montos, porcentajes );
            guardarPiezasAsociadas(detalle.getId(), numeradosAsignados.numeradosUtilizados());
            guardarStockProducto(producto, asignacion.cantidadPiezasAsignada(), numeradosAsignados.pesoRealAsignado());

            return crearResultadoExitoso(
                    producto,
                    detalle,
                    nroLinea,
                    asignacion,
                    montos,
                    numeradosAsignados
            );

        } catch(DataAccessException ex) {
            return crearResultadoErrorExcepcion(
                    producto,
                    detalle,
                    nroLinea,
                    asignacion,
                    montos,
                    ex.getLocalizedMessage()
            );
        }
    }

    private float obtenerPrecioUnitario(VentaDetalleDTO detalle) {
        return detalle.getPrecioUnitario() != null
                ? detalle.getPrecioUnitario().floatValue()
                : 0f;
    }

    private Optional<Producto> buscarProducto(String idProducto) {
        return productoRepository.findById(idProducto);
    }

    private List<Numerado> buscarNumeradosDisponibles(String idProducto) {
        return numeradoRepository.findByProductoIdAndEstadoOrderById(
                idProducto,
                Constants.ESTADO_NUMERADO_DISPONIBLE
        );
    }

    private Porcentajes extraerPorcentajes(VentaDetalleDTO detalle) {
        return new Porcentajes(
                normalizarPorcentaje(detalle.getPorcentajeIva()),
                normalizarPorcentaje(detalle.getPorcentajeIla()),
                normalizarPorcentaje(detalle.getPorcentajeDescuento())
        );
    }

    private float normalizarPorcentaje(BigDecimal valor) {
        if(valor == null) {
            return 0f;
        }
        float porcentaje = valor.floatValue();
        return porcentaje > 1f ? porcentaje / 100f : porcentaje;
    }

    private AsignacionPiezas calcularAsignacion(Producto producto, VentaDetalleDTO detalle) {
        float cantidadPiezasDisponibles = producto.getPieces() != null
                ? producto.getPieces().floatValue()
                : 0f;

        int cantidadPiezasSolicitadas = detalle.getPiezas() != null
                ? detalle.getPiezas().intValue()
                : 0;
        float diferenciaPiezas = cantidadPiezasDisponibles - cantidadPiezasSolicitadas;
        float cantidadPiezasAsignada = diferenciaPiezas < 0
                ? cantidadPiezasDisponibles
                : cantidadPiezasSolicitadas;

        return new AsignacionPiezas(
                cantidadPiezasDisponibles,
                cantidadPiezasSolicitadas,
                diferenciaPiezas,
                cantidadPiezasAsignada
        );
    }

    private NumeradosAsignados asignarNumerados(
            Producto producto,
            List<Numerado> numeradosDisponibles,
            float cantidadPiezasAsignada
    ) {
        float pesoRealAsignado = 0f;
        StringBuilder nombreProductoConNumerados = new StringBuilder(":");
        List<Numerado> numeradosUtilizados = new ArrayList<>();

        int cantidad = (int) cantidadPiezasAsignada;

        for(int i = 0; i < cantidad; i++) {
            Numerado numerado =  numeradosDisponibles.get(i);
            numerado.setEstado(Constants.ESTADO_NUMERADO_VENDIDO);
            numeradoRepository.save(numerado);

            numeradosUtilizados.add(numerado);
            pesoRealAsignado += numerado.getPeso().floatValue();
            nombreProductoConNumerados.append(String.format("%02d ", numerado.getNumero()));
        }
        nombreProductoConNumerados.insert(0, producto.getDescripcion());
        return new NumeradosAsignados(
                numeradosUtilizados,
                pesoRealAsignado,
                nombreProductoConNumerados.toString().trim()
        );
    }

    private MontosVenta calcularMontos(
            Producto producto,
            float pesoRealAsignado,
            Porcentajes porcentajes
    ) {
        float precioVentaNeto = producto.getVentaNeto().floatValue();

        float ventaNetaReal = Math.round(precioVentaNeto * pesoRealAsignado * porcentajes.factorDescuento());
        float valorIvaDeLaVenta = ventaNetaReal * porcentajes.porcentajeIva();
        float valorIlaDeLaVenta = ventaNetaReal * porcentajes.porcentajeIla();
        float valorDescuentoDeLaVenta = precioVentaNeto * pesoRealAsignado * porcentajes.porcentajeDescuento();

        return new MontosVenta(
                ventaNetaReal,
                valorIvaDeLaVenta,
                valorIlaDeLaVenta,
                valorDescuentoDeLaVenta
        );
    }

    private void guardarDetalleDocumento(
            VentaItemContext context,
            Producto producto,
            NumeradosAsignados numeradosAsignados,
            MontosVenta montos,
            Porcentajes porcentajes
    ) {

        int nroLinea = context.nroLinea();
        String numeroLinea = String.format("%03d", nroLinea);
        String identificador = context.idenficador();
        String nombreProductoConNumerados = numeradosAsignados.nombreProductoConNumerados();
        float pesoRealAsignado = numeradosAsignados.pesoRealAsignado();
        float ventaNetaReal = montos.ventaNetaReal();
        float porcentajeDescuento = porcentajes.porcentajeDescuento();

        jdbcFacturacionTemplate.update(
        Constants.INSERT_DETALLE_DOCUMENTO,
                producto.getArticulo(),
                ventaNetaReal,
                Constants.PARIDAD,
                producto.getVentaNeto().floatValue(),
                pesoRealAsignado,
                identificador,
                numeroLinea,
                Constants.TIPO_DOCUMENTO_FACTURA,
                Constants.LOCAL_000,
                producto.getArticulo(),
                nombreProductoConNumerados,
                porcentajeDescuento * -1f);

    }

    private void guardarStockProducto(Producto producto, float cantidadPiezasAsignada, float cantidadAsignada) {
        // Se rebaja de la cantidad de vendidos lo que ya se pasó a facturación.
        float nuevoStockVentas = producto.getStockVentas().floatValue() - cantidadAsignada;
        float nuevoPiezasVentas = producto.getPiezasVentas().floatValue() - cantidadPiezasAsignada;
        producto.setStockVentas(new BigDecimal(nuevoStockVentas));
        producto.setPiezasVentas(new BigDecimal(nuevoPiezasVentas));
        productoRepository.save(producto);
    }

    private void guardarPiezasAsociadas(Long ventaDetalleId, List<Numerado> numeradosUtilizados) {
        if(ventaDetalleId == null || numeradosUtilizados == null || numeradosUtilizados.isEmpty()) {
            return;
        }
        Optional<VentaDetalle> ventaDetalleOptional = ventaDetalleRepository.findById(ventaDetalleId);
        if(ventaDetalleOptional.isPresent()) {
            VentaDetalle ventaDetalle = ventaDetalleOptional.get();
            for(Numerado numerado : numeradosUtilizados) {
                VentaDetallePieza pieza = new VentaDetallePieza();
                pieza.setVentaDetalle(ventaDetalle);
                pieza.setNumerado(numerado);
                pieza.setPeso(numerado.getPeso());
                pieza.setCreadoEn(ventaDetalle.getVenta().getFecha());
                ventaDetallePiezaRepository.save(pieza);
            }
        }
    }

    private VentaItemResultado crearResultadoExitoso(
            Producto producto,
            VentaDetalleDTO detalle,
            int nroLinea,
            AsignacionPiezas asignacion,
            MontosVenta montos,
            NumeradosAsignados numeradosAsignados
    ) {
        List<Numerado> list = numeradosAsignados.numeradosUtilizados();

        List<String> numeradosUtilizados = list == null ? List.of() :
                list.stream().map(Numerado::getNumero).
                filter(Objects::nonNull).map(String::valueOf).
                collect(Collectors.toList());

        NumeracionResultado numeracionResultado = new NumeracionResultado(
                asignacion.cantidadPiezasAsignada(),
                asignacion.diferenciaPiezas(),
                numeradosUtilizados,
                numeradosAsignados.pesoRealAsignado()
        );

        return new VentaItemResultado(
                producto.getArticulo(),
                nroLinea,
                producto.getVentaNeto().floatValue(),
                montos.ventaNetaReal(),
                asignacion.cantidadPiezasSolicitadas(),
                asignacion.diferenciaPiezas(),
                montos.valorIvaDeLaVenta(),
                montos.valorIlaDeLaVenta(),
                montos.valorDescuentoDeLaVenta(),
                numeracionResultado,
                null
        );
    }

    private VentaItemResultado crearResultadoErrorExcepcion(
            Producto producto,
            VentaDetalleDTO detalle,
            int nroLinea,
            AsignacionPiezas asignacion,
            MontosVenta montos,
            String mensajeExcepcion
    ) {
        return new VentaItemResultado(
                producto.getArticulo(),
                nroLinea,
                producto.getVentaNeto().floatValue(),
                montos.ventaNetaReal(),
                asignacion.cantidadPiezasSolicitadas(),
                asignacion.diferenciaPiezas(),
                montos.valorIvaDeLaVenta(),
                montos.valorIlaDeLaVenta(),
                montos.valorDescuentoDeLaVenta(),
                null,
                mensajeExcepcion
        );
    }

    private VentaItemResultado crearResultadoErrorProductoNoExiste(
            VentaDetalleDTO detalle,
            int nroLinea
    ) {
        return new VentaItemResultado(
                detalle.getIdProducto(),
                nroLinea,
                obtenerPrecioUnitario(detalle),
                0f,
                0f,
                0f,
                0f,
                0f,
                0f,
                null,
                "No existe el código de producto"
        );
    }

    private VentaItemResultado crearResultadoErrorSinNumerados(
            VentaDetalleDTO detalle,
            int nroLinea,
            Producto producto
    ) {
        return new VentaItemResultado(
                producto.getArticulo(),
                nroLinea,
                obtenerPrecioUnitario(detalle),
                0f,
                0f,
                0f,
                0f,
                0f,
                0f,
                null,
                "No hay elementos numerados disponibles para el producto"
        );
    }

    private VentaItemResultado crearResultadoErrorSinStockAsignable(
            VentaDetalleDTO detalle,
            int nroLinea,
            Producto producto
    ) {
        return new VentaItemResultado(
                producto.getArticulo(),
                nroLinea,
                obtenerPrecioUnitario(detalle),
                0f,
                0f,
                0f,
                0f,
                0f,
                0f,
                null,
                "No hay stock numerado suficiente para asignar"
        );
    }

}
