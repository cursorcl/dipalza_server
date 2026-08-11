package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.Numerado;
import cl.eos.dipalza.entity.Producto;
import cl.eos.dipalza.mapper.NumeradoMapper;
import cl.eos.dipalza.model.NumeradoDTO;
import cl.eos.dipalza.model.NumeradoResumenDTO;
import cl.eos.dipalza.model.ProductoElegibleNumeradoDTO;
import cl.eos.dipalza.repository.NumeradoRepository;
import cl.eos.dipalza.repository.ProductoRepository;
import cl.eos.dipalza.utils.Constants;
import org.springframework.data.repository.query.Param;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class NumeradosService {

    private final NumeradoRepository numeradoRepository;
    private final ProductoRepository productoRepository;
    private final NumeradoMapper numeradoMapper;

    public NumeradosService(NumeradoRepository numeradoRepository, ProductoRepository productoRepository, NumeradoMapper numeradoMapper) {
        this.numeradoRepository = numeradoRepository;
        this.numeradoMapper = numeradoMapper;
        this.productoRepository = productoRepository;
    }

    public List<NumeradoDTO> findAll() {
        List<Numerado> numerados = numeradoRepository.findAll();
        if(numerados.isEmpty()) {
            return List.of();
        }

        return numerados.stream().map(numeradoMapper::toDTO).collect(Collectors.toList());
    }

    /**
     * Obtiene cuantos disponibles hay de cada numerado.
     * @return
     */
    public List<NumeradoResumenDTO> findGrouped() {
        List<NumeradoResumenDTO> numerados = numeradoRepository.findGroupedByEstado("D");
        return numerados;
    }

    /**
     * Lista los productos marcados como numerado (numbered=true), con su
     * stock/piezas actuales y si tienen algún Numerado asociado en
     * cualquier estado (Disponible, Reservado o Vendido) — ese último dato
     * determina si se pueden desmarcar sin romper historial.
     */
    public List<ProductoElegibleNumeradoDTO> findProductosElegibles() {
        return productoRepository.findByNumberedTrue().stream()
                .map(p -> new ProductoElegibleNumeradoDTO(
                        p.getArticulo(),
                        p.getDescripcion(),
                        p.getStock(),
                        p.getPieces(),
                        numeradoRepository.existsByProducto_Articulo(p.getArticulo())
                ))
                .collect(Collectors.toList());
    }

    @Transactional
    public void marcarProductoComoNumerado(String articulo) {
        Producto producto = productoRepository.findByArticulo(articulo);
        if (producto == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Producto no encontrado");
        }
        if (Boolean.TRUE.equals(producto.getNumbered())) {
            return;
        }
        producto.setNumbered(true);
        productoRepository.save(producto);
    }

    @Transactional
    public void desmarcarProductoComoNumerado(String articulo) {
        Producto producto = productoRepository.findByArticulo(articulo);
        if (producto == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Producto no encontrado");
        }
        if (numeradoRepository.existsByProducto_Articulo(articulo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No se puede quitar: el producto tiene numerados asociados");
        }
        producto.setNumbered(false);
        productoRepository.save(producto);
    }

    public List<NumeradoDTO> findAllByEstado(@Param("estado") String estado) {
        List<Numerado> numerados = numeradoRepository.findByEstado(estado);
        if(numerados.isEmpty()) {
            return List.of();
        }
        return numerados.stream().map(numeradoMapper::toDTO).collect(Collectors.toList());
    }

    public NumeradoDTO findById(Long id) {

        Optional<Numerado> numerado = numeradoRepository.findById(id);
        if(numerado.isPresent()) {
            return numeradoMapper.toDTO(numerado.get());
        }
        return null;
    }

    public List<NumeradoDTO> findByProducto(String idProducto) {
        List<Numerado> numerados = numeradoRepository.findByProductoId(idProducto);
        if(numerados.isEmpty()) {
            return List.of();
        }
        return numerados.stream()
                .filter(n -> !Constants.ESTADO_NUMERADO_VENDIDO.equals(n.getEstado()))
                .map(numeradoMapper::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public NumeradoDTO save(NumeradoDTO n) {
        Producto producto = productoRepository.findByArticulo(n.getCodigoProducto());
        if(producto == null) {
            return null;
        }
        if(!Boolean.TRUE.equals(producto.getNumbered())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El producto no está marcado como numerado");
        }
        if(numeradoRepository.existsNumeroActivoParaProducto(producto.getArticulo(), n.getNumero(), n.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ya existe un numerado activo con ese número para este producto");
        }

        Numerado numerado = (n.getId() != null)
                ? numeradoRepository.findById(n.getId()).orElse(new Numerado())
                : new Numerado();

        numerado.setProducto(producto);
        numerado.setNumero(n.getNumero());
        numerado.setEstado(n.getEstado());
        numerado.setPeso(n.getPeso());
        numeradoRepository.save(numerado);

        actualizarPiezasDisponibles(producto);

        return numeradoMapper.toDTO(numerado);
    }

    @Transactional
    public void deleteById(Long id) {
        Numerado numerado = numeradoRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Numerado no encontrado"));
        if(!Constants.ESTADO_NUMERADO_DISPONIBLE.equals(numerado.getEstado())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Solo se pueden eliminar numerados en estado Disponible");
        }
        Producto producto = numerado.getProducto();

        numeradoRepository.deleteById(id);

        actualizarPiezasDisponibles(producto);
    }

    /**
     * Recalcula producto.pieces como el conteo de numerados en estado
     * Disponible para ese producto. Se recalcula por conteo (no delta) para
     * que nunca pueda desincronizarse: usa el mismo criterio que ya expone
     * GET /api/numerados/resumen.
     */
    private void actualizarPiezasDisponibles(Producto producto) {
        int piezas = numeradoRepository
                .findByProductoIdAndEstadoOrderById(producto.getArticulo(), Constants.ESTADO_NUMERADO_DISPONIBLE)
                .size();
        producto.setPieces(BigDecimal.valueOf(piezas));
        productoRepository.save(producto);
    }

    public Float findPrecioPromedioArticulo(String articulo) {
        List<Numerado> lista = numeradoRepository.findByProductoId(articulo);
        if(lista.isEmpty()) {
            return 0f;
        }
        BigDecimal promedio = lista.stream()
                .map(nn -> nn.getPeso())
                .reduce(BigDecimal.ZERO, BigDecimal::add) // Suma total empezando desde 0
                .divide(new BigDecimal(lista.size())); // División formal

        return promedio.floatValue();
    }

}
