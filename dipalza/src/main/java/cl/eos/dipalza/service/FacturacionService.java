package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.EstadoVenta;
import cl.eos.dipalza.entity.Producto;
import cl.eos.dipalza.entity.Venta;
import cl.eos.dipalza.entity.VentaDetalle;
import cl.eos.dipalza.exceptions.NumeroFolioException;
import cl.eos.dipalza.mapper.ProductoMapper;
import cl.eos.dipalza.mapper.VentaMapper;
import cl.eos.dipalza.model.venta.VentaDTO;
import cl.eos.dipalza.model.venta.VentaDetalleDTO;
import cl.eos.dipalza.repository.IlaRepository;
import cl.eos.dipalza.repository.ProductoRepository;
import cl.eos.dipalza.repository.VentaDetalleRepository;
import cl.eos.dipalza.repository.VentaRepository;
import cl.eos.dipalza.service.dtos.ResumenILAVenta;
import cl.eos.dipalza.service.grabacion.VentaItemContext;
import cl.eos.dipalza.service.grabacion.VentaItemProcessorResolver;
import cl.eos.dipalza.service.resultados.VentaFacturaResultado;
import cl.eos.dipalza.service.resultados.VentaItemResultado;
import cl.eos.dipalza.specifications.VentaFilter;
import cl.eos.dipalza.specifications.VentaSpecifications;
import cl.eos.dipalza.utils.Constants;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class FacturacionService {

    public static final String NUMERO_LINEAS_FACTURA = "NUMERO_LINEAS_FACTURA";
    public static final String FACTURA_ELECTRONICA = "FACTURA_ELECTRONICA";
    private final JdbcTemplate jdbcFacturacionTemplate;
    private final ConfiguracionService configuracion;

    /// Autowired components
    private final ProductoRepository productoRepository;
    private final IlaRepository ilaRepository;
    private final boolean facturaElectronica;
    private final int nroLineasPorFactura;
    private final String tipoFacturaName;
    private final PlatformTransactionManager transactionManager;
    private final ProductoMapper productoMapper;
    private final VentaItemProcessorResolver resolver;
    private final VentaRepository ventaRepository;
    private final VentaDetalleRepository ventaDetalleRepository;

    public FacturacionService(
            @Qualifier("facturacionTransactionManager") PlatformTransactionManager transactionManager, // Usar el nuevo
            @Qualifier("facturacionJdbcTemplate") JdbcTemplate jdbcFacturacionTemplate,
            VentaItemProcessorResolver resolver,
            VentaRepository ventaRepository,
            VentaDetalleRepository ventaDetalleRepository,
            ConfiguracionService configuracion,
            ProductoRepository productoRepository,
            IlaRepository ilaRepository,
            ProductoMapper productoMapper

    ) {
        this.jdbcFacturacionTemplate = jdbcFacturacionTemplate;
        this.transactionManager = transactionManager;
        this.configuracion = configuracion;
        this.productoRepository = productoRepository;
        this.ilaRepository = ilaRepository;
        this.productoMapper = productoMapper;
        this.resolver = resolver;
        this.ventaRepository = ventaRepository;
        this.ventaDetalleRepository = ventaDetalleRepository;

        this.facturaElectronica = this.configuracion.getBoolean(FACTURA_ELECTRONICA);
        this.tipoFacturaName = this.facturaElectronica ? "E" : " ";
        this.nroLineasPorFactura = this.configuracion.getInt(NUMERO_LINEAS_FACTURA);

    }

    public List<VentaFacturaResultado> facturar() {

        List<String> estados = List.of("FINISHED");
        VentaFilter filter = new VentaFilter(estados, null, null, null, null, null, null);
        Specification<Venta> specification = VentaSpecifications.toSpecification(filter);

        List<Venta> ventas = ventaRepository.findAll(specification);
        VentaMapper mapper = new VentaMapper();
        List<VentaDTO> ventasDTO = ventas.stream().map(v -> mapper.toVentaDTO(v)).toList();

        return facturar(ventasDTO);
    }

    public List<VentaFacturaResultado> facturar(List<VentaDTO> ventas) {
        List<VentaFacturaResultado> results = new ArrayList<>();
        // Usamos el gestor de la base externa
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        // Solo hará rollback si ocurre una RuntimeException o Error dentro del execute
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        ///  Se recorre la lista de ventas y se procesa una por una.
        for(VentaDTO venta : ventas) {
            try {
                VentaFacturaResultado resultado = transactionTemplate.execute(status -> {
                    // Si procesarVenta lanza una excepción, JDBC hará rollback aquí
                    return procesarVenta(venta);
                });
                if(resultado != null) results.add(resultado);
            } catch(Exception e) {
                // Aquí capturamos la falla para seguir con el siguiente vendedor de la cola
                //results.add(new VentaFacturaResultado(venta.getId(), false, "Fallo: " + e.getMessage(), ""));
                // TODO Debo agregar una entrada que me indique que hay error.
            }
        }
        return results;
    }

    /**
     * Procesa una venta y genera el resultado de facturación para cada uno de sus detalles.
     *
     * <h3>Descripción</h3>
     * Analiza cada ítem de la venta y aplica las reglas de asignación según el tipo de
     * producto (numerado o no numerado). Para cada detalle se genera un resultado que
     * indica cantidades asignadas, faltantes y posibles errores.
     *
     * <h3>Reglas de negocio</h3>
     * <ul>
     *   <li>Si el producto es numerado, se asignan piezas específicas desde inventario.</li>
     *   <li>Si el producto no es numerado, se asigna la cantidad disponible.</li>
     *   <li>Si no existe suficiente inventario, se registra la cantidad faltante.</li>
     *   <li>Si ocurre un error de validación, el resultado incluirá el mensaje correspondiente.</li>
     * </ul>
     *
     * <h3>Comportamiento</h3>
     * <ul>
     *   <li>El método no persiste cambios en base de datos.</li>
     *   <li>El procesamiento es determinístico para una misma venta.</li>
     *   <li>La lista retornada contiene un resultado por cada detalle de venta.</li>
     * </ul>
     *
     * <h3>Ejemplo</h3>
     * Venta con dos ítems:
     * <pre>
     * Producto A (no numerado) - cantidad 10kg
     * Producto B (numerado)    - 3 piezas
     * </pre>
     * <p>
     * Resultado esperado:
     * <pre>
     * ResultadoFacturacionDTO[
     *   {producto=A, asignado=10, faltante=0},
     *   {producto=B, piezasAsignadas=[1001,1002,1003]}
     * ]
     * </pre>
     *
     * @param venta venta a procesar con sus detalles
     * @return lista de resultados del procesamiento de cada ítem
     */
    private VentaFacturaResultado procesarVenta(VentaDTO venta) {

        /// Validaciones

        // La venta es nula.
        if(venta == null)
            return new VentaFacturaResultado("", LocalDateTime.now(), BigDecimal.ZERO, null, "A tratado de facturar un registro vacío");

        // El Estado actual de la venta no es facturable.
        if(!isFacturable(venta))
            return new VentaFacturaResultado("", LocalDateTime.now(), BigDecimal.ZERO, null, "Estado de la venta %d no permite su facturación !!".formatted(venta.getId()));

        // La venta viene sin registros de detalle, es decir no vendió productos.
        List<VentaDetalle> detalles = ventaDetalleRepository.findAllOptimizedByVentaId(venta.getId());
        if(detalles == null || detalles.isEmpty())
            return new VentaFacturaResultado("", LocalDateTime.now(), BigDecimal.ZERO, null, "La venta %d no tiene detalles.".formatted(venta.getId()));

        List<VentaDetalleDTO> detalleDTOs = detalles.stream().map(v -> VentaMapper.toVentaDetalleDTO(v)).toList();

        // Se asigna manualmente porque el query original no trae el detalle.
        venta.setDetalles(detalleDTOs);

        List<VentaItemResultado> result = new ArrayList<>();

        float totalVentaNeto = 0;
        float totalIva = 0f;
        float totalIla = 0f;
        float totalDescuento = 0f;

        int nroLinea = 1;
        String nroFactura = null;
        String identificador = null;

        ///  Se procesa cada uno de los registros de la venta
        for(int n = 0; n < venta.getDetalles().size(); n++) {
            VentaDetalleDTO detalle = venta.getDetalles().get(n);
            try {
                if(n % nroLineasPorFactura == 0) {
                    /// Se reinician el contador de filas y los acumuladores de venta.
                    nroLinea = 1;
                    totalVentaNeto = 0f;
                    totalIva = 0f;
                    totalIla = 0f;
                    totalDescuento = 0f;

                    identificador = generarObtenerIdentificador();
                    nroFactura = generarObtenerNumeroFactura(this.tipoFacturaName);

                    grabarEncabezadoVenta(venta, nroFactura, identificador);
                }

                /// Se envía a grabar a la base de datos el registro
                VentaItemResultado resultado = grabarUnItemDetalleVenta(detalle, nroFactura, identificador, nroLinea);
                result.add(resultado);

                totalVentaNeto += resultado.valorTotalVentaNeta();
                totalIva += resultado.valorTotalIva();
                totalIla += resultado.valorTotalIla();
                totalDescuento += resultado.valorTotalDescuento();

                nroLinea++;

            } catch(NumeroFolioException ex) {

            } catch(EmptyResultDataAccessException ex) {
                throw new RuntimeException("Producto no encontrado en BD destino: " + detalle.getIdProducto());
            }
        }

        grabarIla(identificador, nroFactura);
        grabarTotalDocumento(identificador, totalVentaNeto, totalIva, totalIla);
        grabarCuentaDocumento(venta, nroFactura, totalVentaNeto, totalIva, totalIla);
        grabarEnFolio(nroFactura, this.tipoFacturaName, Constants.TIPO_DOCUMENTO_FACTURA);

        actualizarVentaFacturado(venta);
        actualizarStockProductos(venta);

        return new VentaFacturaResultado(nroFactura, LocalDateTime.now(), BigDecimal.ZERO, result, "Se ha grabado exitosamente la venta!!");
    }

    /**
     * Resta del acumulado de ventas que lleva el producto la cantidad que realmente fue vendida.
     *
     * @param venta registro de venta de un cliente que contiene el listado de ventas.
     */
    private void actualizarStockProductos(VentaDTO venta) {

        if(venta == null || venta.getDetalles() == null || venta.getDetalles().isEmpty())
            return;

        for(VentaDetalleDTO detalle : venta.getDetalles()) {

            Producto producto = productoRepository.findById(detalle.getIdProducto()).orElse(null);
            if(producto == null)
                continue;

            var stockVentasActual = producto.getStockVentas();
            if(stockVentasActual == null)
                continue;

            var piezasVentasActual = producto.getPiezasVentas();
            if(piezasVentasActual == null)
                continue;

            // stock no puede ser negativo
            stockVentasActual = stockVentasActual.subtract(detalle.getCantidad()).max(BigDecimal.ZERO);;
            // cantidad de piezas no puede ser negativa
            piezasVentasActual = piezasVentasActual.subtract(detalle.getPiezas()).max(BigDecimal.ZERO);

            producto.setStockVentas(stockVentasActual);
            producto.setPiezasVentas(piezasVentasActual);
            productoRepository.save(producto);
        }
    }

    /**
     * Graba el encabezado de una factura.
     *
     * <p>En el registro se asocia el número de factura con el identificador dentro de la base de datos.</p>
     *
     * @param venta         Registro de venta a procesar, contiene todos los registros de venta de productos.
     * @param numeroFactura El número de factura que se le va a asignar.
     * @param idBaseDatos   El identificador de base de datos asociado.
     */
    private void grabarEncabezadoVenta(VentaDTO venta, String numeroFactura, String idBaseDatos) {
        String sql = "insert into encabezadocumento (fecha, vence, afectoexento, rut, local, id, tipo, numero, codigo, tipo1, publicadonro ) values  (?, ?, ?, ?, ?, ?, ?, ?, ?,?,?)";
        LocalDate fechaVencimiento = venta.getFecha().plusDays(1);

        try {
            jdbcFacturacionTemplate.update(sql,
                    Date.valueOf(venta.getFecha()),
                    Date.valueOf(fechaVencimiento),
                    "A",
                    venta.getRutCliente(),
                    "000",
                    idBaseDatos,
                    "06",
                    numeroFactura,
                    venta.getCodigoCliente() + " ",
                    this.tipoFacturaName,
                    numeroFactura
            );
        } catch(Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Graba el registro <code>detalle</code> en la base de datos utilizando el patrón <code>strategy</code> para determinar
     * si es numerado o no numerado.
     */
    private VentaItemResultado grabarUnItemDetalleVenta(VentaDetalleDTO detalle, String numeroFactura,
                                                        String identificador, int nroLinea) {
        var context = new VentaItemContext(detalle, numeroFactura, identificador, nroLinea);
        var processor = resolver.resolve(context);
        var resultado = processor.procesar(context);
        return resultado;
    }

    /**
     * Almacena el total de la factura en la tabla TotalDocumento
     *
     * @param identificador identificador de la base de datos
     * @param totalVenta    total de vneta neto
     * @param totalIVA      total de iva de la factura
     * @param totalILA      total de ila de la factura
     * @return número de filas grabadas
     */
    private Integer grabarTotalDocumento(String identificador, float totalVenta, float totalIVA, float totalILA) {
        String sql =
                """
                           insert into totaldocumento 
                            (totaldetalle, totaliva, totalila, totalneto, total, id, tipoid) values 
                            (?, ?, ?, ?, ?, ?, ?)
                        """;
        float totalBruto = totalVenta + totalIVA + totalILA;
        try {
            int rowsAffected =  jdbcFacturacionTemplate.update(sql,
                    totalVenta,
                    totalIVA,
                    totalILA,
                    totalVenta,
                    totalBruto,
                    identificador,
                    Constants.TIPO_DOCUMENTO_FACTURA);

            return rowsAffected;
        } catch(DataAccessException e) {
            throw new RuntimeException("Error grabando Total Documento", e);
        }
        catch(Exception ex) {
            ex.printStackTrace();
        }
        return null;

    }

    /**
     * Graba en la tabla CuentaDocumento de MASTERSOFT
     */
    private Integer grabarCuentaDocumento(VentaDTO venta, String nroFactura, float totalVenta, float totalIVA, float totalILA) {

        String codigoVendedor = venta.getCodigoVendedor();
        String codigoCliente = venta.getCodigoCliente();
        float comision = obtenerComisionVendedor(codigoVendedor);
        int numeroDiasComisionVenta = obtenerNumeroDiasAsociadosCondicionVenta(venta.getCodigoCondicionVenta());
        String rutCliente = venta.getRutCliente();
        float totalVentaBruto = totalVenta + totalIVA + totalILA;

        LocalDate fechaVenta = venta.getFecha();
        LocalDate fechaVencimiento = fechaVenta.plusDays(numeroDiasComisionVenta);

        String sqlInsert = """
                insert into ctadocto 
                (rut_cliente, fecha_vencimiento, comision, fecha_ingreso, vendedor, valor_bruto, valor_iva, valor_neto, tipo, numero, codigo_cliente, local_venta, valor_ila, TIPO1) 
                values 
                (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try {
            int rowsAffected = jdbcFacturacionTemplate.update(sqlInsert,
                    rutCliente,
                    fechaVenta,
                    comision,
                    fechaVencimiento,
                    codigoVendedor,
                    totalVentaBruto,
                    totalIVA,
                    totalVenta,
                    Constants.TIPO_DOCUMENTO_FACTURA,
                    nroFactura,
                    codigoCliente,
                    Constants.LOCAL_000,
                    totalILA,
                    this.tipoFacturaName
            );
            return rowsAffected;
        } catch(DataAccessException e) {
            throw new RuntimeException("Error grabando Cuenta Documento", e);
        }
        catch(Exception ex) {
            ex.printStackTrace();
        }
        return null;

    }

    /**
     * Graba en la tabla Ila de MASTERSOFT
     */
    private void grabarIla(String identificador, String nroFactura) {
        ///  Se calcula el total de cada código de ILA para insertarlo en la BD.
        String queryIla = "SELECT A.CodigoIla, C.valor as PorcIla, D.TipoId, sum(TotalLinea * C.valor /100)  AS Valor FROM ARTICULO A, MSOSTTABLAS C, DETALLEDOCUMENTO D WHERE  A.Articulo = D.Articulo AND D.Id = ? and C.codigo=A.CodigoIla and C.descripcion like '%ILA%' Group by A.CodigoIla, C.valor, D.TipoId  ";

        try {
            List<ResumenILAVenta> resultados = jdbcFacturacionTemplate.query(
                    queryIla,
                    new DataClassRowMapper<>(ResumenILAVenta.class),
                    identificador
            );

            String sqlInsertIla = "insert into MSOSTVENTASILA (tipo, TIPO1, codigo, valor, numero, ila) values (?, ?, ?, ?, ?, ?)";
            for(ResumenILAVenta r : resultados) {
                jdbcFacturacionTemplate.update(sqlInsertIla,
                        r.tipoId(),
                        this.tipoFacturaName,
                        r.codigoIla(),
                        r.valor(),
                        nroFactura,
                        r.porcIla()
                );
            }
        } catch(Exception e) {
            e.printStackTrace();
        }

    }

    /**
     * Este método le cambia el estado al registro de venta al asignado por el parámetro.
     * <p>
     * El estado CLOSED indica que la venta fue transferida hacia el sistema MASTERSOFT.
     * El estado OPENED indica que la
     *
     * @param venta La venta a l
     */
    private boolean actualizarVentaFacturado(VentaDTO venta) {
        Venta eVenta = ventaRepository.findByIdOptimized(venta.getId()).orElse(null);
        if(eVenta == null) return false;

        eVenta.setEstado(EstadoVenta.CLOSED);
        ventaRepository.save(eVenta);

        return true;

    }

    /// SECCIÓN DE UTILITARIOS

    /**
     * Actualiza la tabla parámetros con el último número de folios.
     * <p>
     * A lo más hay un registro, por lo que se valida la existencia de registros antes del update que se encarga de agregar o actualizar.
     *
     * @return String con formato requerido del número de folio.
     */
    private String generarObtenerIdentificador() {
        String sql = """
                		IF NOT EXISTS (SELECT 1 FROM PARAMETROS)
                		BEGIN
                			INSERT INTO PARAMETROS (FolioDocumento)
                			VALUES ('0000000000')
                		END
                		UPDATE PARAMETROS
                		SET FolioDocumento = RIGHT('0000000000' + CAST(CAST(FolioDocumento AS BIGINT) + 1 AS VARCHAR(20)), 10)
                		OUTPUT INSERTED.FolioDocumento;
                """;
        return jdbcFacturacionTemplate.queryForObject(sql, String.class);
    }


    /**
     * Genera el nuevo número de factura
     * @param tipoFactura
     * @return
     */
    private String generarObtenerNumeroFactura(String tipoFactura) {
        int intentos = 0;
        int maxIntentos = 5; // Intentará 5 veces antes de rendirse

        while(intentos < maxIntentos) {
            try {
                // CALCULAR: Buscamos el máximo actual y sumamos 1
                // COALESCE(..., 0) maneja el caso de la tabla vacía.
                String sqlMax = "SELECT COALESCE(MAX(CAST(numero AS BIGINT)), 0) + 1 FROM folios WHERE tipo = '06' AND tipo1 = ?";

                Long numeroCalculado = jdbcFacturacionTemplate.queryForObject(sqlMax, Long.class, tipoFactura);

                // FORMATEAR: Convertimos a String con 7 dígitos y ceros a la izquierda
                // ÉXITO: Si llegamos aquí, nadie nos ganó el número. Lo retornamos.
                return  String.format("%07d", numeroCalculado);

            } catch(DuplicateKeyException e) {
                // 5. FALLO: Alguien insertó ese número milisegundos antes que nosotros.
                // No lanzamos error, simplemente aumentamos el contador y el 'while' repetirá
                // el proceso.
                intentos++;
                System.out.println("Colisión de folios detectada. Reintentando... Intento " + intentos);
            }
        }

        // Si sale del while, falló 5 veces seguidas (sistema muy saturado)
        throw new NumeroFolioException(
                "No se pudo obtener un folio después de " + maxIntentos + " intentos. Intente nuevamente.");
    }

    private Integer grabarEnFolio(String numeroFactura, String tipoFactura, String tipoDocumento) {
        // Es vital que la tabla 'folios' tenga UNIQUE CONSTRAINT en (tipo, tipo1,  numero)
        String sqlInsert = "INSERT INTO folios (numero, tipo1, tipo) VALUES (?, ?, ?)";

        int rowsAffected =  jdbcFacturacionTemplate.update(sqlInsert, numeroFactura, tipoFactura, tipoDocumento);
        return rowsAffected;
    }

    private int obtenerNumeroDiasAsociadosCondicionVenta(String condicionVenta) {
        String sql = """
                select valor from msosttablas where tabla = '009' and codigo = ?
                """;

        // Usamos BigDecimal para precisión financiera y evitamos excepciones de flujo
        BigDecimal numeroDias = jdbcFacturacionTemplate.query(sql, rs -> {
            if (rs.next()) {
                BigDecimal val = rs.getBigDecimal("valor");
                return (val != null) ? val : BigDecimal.ZERO;
            }
            return BigDecimal.ZERO;
        }, condicionVenta);

        return numeroDias.intValue();
    }

    /**
     * Obtiene la comisión de venta asociado a un vendedor.
     *
     * @param codigoVendedor código del vendedor al que se le busca la comisión.
     * @return el valor de la comisión o un 0 para que no afecte
     */
    private float obtenerComisionVendedor(String codigoVendedor) {
        String sql = "SELECT comision FROM msovendedor WHERE codigo = ?";

        // Usamos BigDecimal para precisión financiera y evitamos excepciones de flujo
        BigDecimal comision = jdbcFacturacionTemplate.query(sql, rs -> {
            if (rs.next()) {
                BigDecimal val = rs.getBigDecimal("comision");
                return (val != null) ? val : BigDecimal.ZERO;
            }
            return BigDecimal.ZERO;
        }, codigoVendedor);

        return comision.floatValue();
    }

    /**
     * Determina si la venta es facturable dependiendo del estado.
     *
     * @param venta la venta que se quiere determinar si es facturable.
     */
    private boolean isFacturable(VentaDTO venta) {
        EstadoVenta estadoVenta = EstadoVenta.fromName(venta.getEstadoVenta());
        return estadoVenta != null && estadoVenta.equals(EstadoVenta.FINISHED);
    }
}
