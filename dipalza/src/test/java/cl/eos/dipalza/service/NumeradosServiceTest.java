package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.Numerado;
import cl.eos.dipalza.entity.Producto;
import cl.eos.dipalza.mapper.NumeradoMapper;
import cl.eos.dipalza.model.NumeradoDTO;
import cl.eos.dipalza.model.ProductoElegibleNumeradoDTO;
import cl.eos.dipalza.repository.NumeradoRepository;
import cl.eos.dipalza.repository.ProductoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NumeradosServiceTest {

    @Mock NumeradoRepository numeradoRepo;
    @Mock ProductoRepository productoRepo;
    @Mock NumeradoMapper mapper;
    @InjectMocks NumeradosService service;

    private Numerado numerado(Long id, String productoId, BigDecimal peso) {
        Numerado n = new Numerado();
        n.setId(id);
        n.setEstado("D");
        n.setNumero(1);
        n.setPeso(peso);
        Producto p = new Producto();
        p.setArticulo(productoId);
        p.setNumbered(true);
        n.setProducto(p);
        return n;
    }

    private Producto productoNumerado(String articulo) {
        Producto p = new Producto();
        p.setArticulo(articulo);
        p.setNumbered(true);
        return p;
    }

    private NumeradoDTO dto(Long id) {
        NumeradoDTO d = new NumeradoDTO();
        d.setId(id);
        d.setCodigoProducto("ART001");
        d.setNumero(1);
        d.setPeso(BigDecimal.valueOf(10));
        d.setEstado("D");
        return d;
    }

    @Test
    void findAll_listaVacia_retornaEmpty() {
        when(numeradoRepo.findAll()).thenReturn(List.of());
        assertThat(service.findAll()).isEmpty();
    }

    @Test
    void findAll_conElementos_retornaDTOs() {
        when(numeradoRepo.findAll()).thenReturn(List.of(numerado(1L, "ART001", BigDecimal.TEN)));
        when(mapper.toDTO(any())).thenReturn(dto(1L));
        assertThat(service.findAll()).hasSize(1);
    }

    @Test
    void findByProducto_conElementos_retornaDTOs() {
        when(numeradoRepo.findByProductoId("ART001")).thenReturn(List.of(numerado(1L, "ART001", BigDecimal.TEN)));
        when(mapper.toDTO(any())).thenReturn(dto(1L));
        assertThat(service.findByProducto("ART001")).hasSize(1);
    }

    @Test
    void findByProducto_excluyeVendidos_retornaSoloDisponibles() {
        Numerado disponible = numerado(1L, "ART001", BigDecimal.TEN);
        disponible.setEstado("D");
        Numerado vendido = numerado(2L, "ART001", BigDecimal.ONE);
        vendido.setEstado("V");
        when(numeradoRepo.findByProductoId("ART001")).thenReturn(List.of(disponible, vendido));
        when(mapper.toDTO(disponible)).thenReturn(dto(1L));

        List<NumeradoDTO> result = service.findByProducto("ART001");

        assertThat(result).hasSize(1).extracting(NumeradoDTO::getId).containsExactly(1L);
        verify(mapper, never()).toDTO(vendido);
    }

    @Test
    void findById_existente_retornaDTO() {
        when(numeradoRepo.findById(1L)).thenReturn(Optional.of(numerado(1L, "ART001", BigDecimal.TEN)));
        when(mapper.toDTO(any())).thenReturn(dto(1L));
        assertThat(service.findById(1L)).isNotNull().extracting(NumeradoDTO::getId).isEqualTo(1L);
    }

    @Test
    void save_productoNoExiste_retornaNull() {
        when(productoRepo.findByArticulo("NOEXISTE")).thenReturn(null);
        NumeradoDTO d = dto(null);
        d.setCodigoProducto("NOEXISTE");
        assertThat(service.save(d)).isNull();
    }

    @Test
    void save_productoNoNumerado_lanzaExcepcion400() {
        Producto prod = new Producto();
        prod.setArticulo("ART001");
        prod.setNumbered(false);
        when(productoRepo.findByArticulo("ART001")).thenReturn(prod);

        assertThatThrownBy(() -> service.save(dto(null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no está marcado como numerado");
    }

    @Test
    void save_numeroDuplicado_lanzaExcepcion400() {
        when(productoRepo.findByArticulo("ART001")).thenReturn(productoNumerado("ART001"));
        when(numeradoRepo.existsNumeroActivoParaProducto("ART001", 1, null)).thenReturn(true);

        assertThatThrownBy(() -> service.save(dto(null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Ya existe un numerado activo");
    }

    @Test
    void save_altaConIdNulo_noConsultaFindByIdYGuardaCorrectamente() {
        Producto prod = productoNumerado("ART001");
        when(productoRepo.findByArticulo("ART001")).thenReturn(prod);
        when(numeradoRepo.existsNumeroActivoParaProducto("ART001", 1, null)).thenReturn(false);
        when(mapper.toDTO(any(Numerado.class))).thenReturn(dto(1L));

        NumeradoDTO result = service.save(dto(null));

        assertThat(result).isNotNull();
        verify(numeradoRepo, never()).findById(isNull());
        verify(numeradoRepo).save(any(Numerado.class));
    }

    @Test
    void save_productoExiste_guardaYRetornaDTO() {
        Producto prod = productoNumerado("ART001");
        when(productoRepo.findByArticulo("ART001")).thenReturn(prod);
        when(numeradoRepo.findById(any())).thenReturn(Optional.empty());
        Numerado saved = numerado(1L, "ART001", BigDecimal.TEN);
        when(numeradoRepo.save(any())).thenReturn(saved);
        when(mapper.toDTO(any(Numerado.class))).thenReturn(dto(1L));

        NumeradoDTO result = service.save(dto(1L));
        assertThat(result).isNotNull();
    }

    @Test
    void save_actualizaPiezasDisponiblesDelProducto() {
        Producto prod = productoNumerado("ART001");
        when(productoRepo.findByArticulo("ART001")).thenReturn(prod);
        when(numeradoRepo.existsNumeroActivoParaProducto(anyString(), any(), any())).thenReturn(false);
        when(mapper.toDTO(any(Numerado.class))).thenReturn(dto(1L));
        when(numeradoRepo.findByProductoIdAndEstadoOrderById(eq("ART001"), eq("D")))
                .thenReturn(List.of(numerado(1L, "ART001", BigDecimal.TEN), numerado(2L, "ART001", BigDecimal.ONE)));

        service.save(dto(null));

        ArgumentCaptor<Producto> captor = ArgumentCaptor.forClass(Producto.class);
        verify(productoRepo).save(captor.capture());
        assertThat(captor.getValue().getPieces()).isEqualByComparingTo(BigDecimal.valueOf(2));
    }

    @Test
    void deleteById_existente_eliminaYActualizaPiezas() {
        Numerado n = numerado(5L, "ART001", BigDecimal.TEN);
        when(numeradoRepo.findById(5L)).thenReturn(Optional.of(n));
        when(numeradoRepo.findByProductoIdAndEstadoOrderById("ART001", "D")).thenReturn(List.of());

        service.deleteById(5L);

        verify(numeradoRepo).deleteById(5L);
        ArgumentCaptor<Producto> captor = ArgumentCaptor.forClass(Producto.class);
        verify(productoRepo).save(captor.capture());
        assertThat(captor.getValue().getPieces()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void deleteById_noExistente_lanza404() {
        when(numeradoRepo.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteById(99L))
                .isInstanceOf(ResponseStatusException.class);

        verify(numeradoRepo, never()).deleteById(any());
    }

    @Test
    void deleteById_estadoNoDisponible_lanza400YNoElimina() {
        Numerado vendido = numerado(5L, "ART001", BigDecimal.TEN);
        vendido.setEstado("V");
        when(numeradoRepo.findById(5L)).thenReturn(Optional.of(vendido));

        assertThatThrownBy(() -> service.deleteById(5L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Solo se pueden eliminar numerados en estado Disponible");

        verify(numeradoRepo, never()).deleteById(any());
    }

    @Test
    void findPrecioPromedio_listaVacia_retornaCero() {
        when(numeradoRepo.findByProductoId("ART001")).thenReturn(List.of());
        assertThat(service.findPrecioPromedioArticulo("ART001")).isEqualTo(0f);
    }

    @Test
    void findPrecioPromedio_conElementos_retornaPromedio() {
        List<Numerado> lista = List.of(
                numerado(1L, "ART001", BigDecimal.valueOf(10)),
                numerado(2L, "ART001", BigDecimal.valueOf(20))
        );
        when(numeradoRepo.findByProductoId("ART001")).thenReturn(lista);
        assertThat(service.findPrecioPromedioArticulo("ART001")).isEqualTo(15f);
    }

    @Test
    void findProductosElegibles_retornaSoloNumberedTrueConDatosDelProducto() {
        Producto p = productoNumerado("ART001");
        p.setDescripcion("Queso");
        p.setStock(BigDecimal.valueOf(50));
        p.setPieces(BigDecimal.valueOf(3));
        when(productoRepo.findByNumberedTrue()).thenReturn(List.of(p));
        when(numeradoRepo.existsByProducto_Articulo("ART001")).thenReturn(false);

        List<ProductoElegibleNumeradoDTO> result = service.findProductosElegibles();

        assertThat(result).hasSize(1);
        ProductoElegibleNumeradoDTO dto = result.get(0);
        assertThat(dto.codigoProducto()).isEqualTo("ART001");
        assertThat(dto.nombreProducto()).isEqualTo("Queso");
        assertThat(dto.stock()).isEqualByComparingTo(BigDecimal.valueOf(50));
        assertThat(dto.piezas()).isEqualByComparingTo(BigDecimal.valueOf(3));
        assertThat(dto.tieneRegistrosAsociados()).isFalse();
    }

    @Test
    void findProductosElegibles_marcaTieneRegistrosAsociadosSiExisteAlgunNumerado() {
        Producto p = productoNumerado("ART002");
        when(productoRepo.findByNumberedTrue()).thenReturn(List.of(p));
        when(numeradoRepo.existsByProducto_Articulo("ART002")).thenReturn(true);

        List<ProductoElegibleNumeradoDTO> result = service.findProductosElegibles();

        assertThat(result.get(0).tieneRegistrosAsociados()).isTrue();
    }

    @Test
    void marcarProductoComoNumerado_productoNoExiste_lanza404() {
        when(productoRepo.findByArticulo("NOEXISTE")).thenReturn(null);

        assertThatThrownBy(() -> service.marcarProductoComoNumerado("NOEXISTE"))
                .isInstanceOf(ResponseStatusException.class);

        verify(productoRepo, never()).save(any());
    }

    @Test
    void marcarProductoComoNumerado_yaMarcado_esNoOp() {
        Producto p = productoNumerado("ART001");
        when(productoRepo.findByArticulo("ART001")).thenReturn(p);

        service.marcarProductoComoNumerado("ART001");

        verify(productoRepo, never()).save(any());
    }

    @Test
    void marcarProductoComoNumerado_noMarcado_loMarcaYGuarda() {
        Producto p = new Producto();
        p.setArticulo("ART001");
        p.setNumbered(false);
        when(productoRepo.findByArticulo("ART001")).thenReturn(p);

        service.marcarProductoComoNumerado("ART001");

        ArgumentCaptor<Producto> captor = ArgumentCaptor.forClass(Producto.class);
        verify(productoRepo).save(captor.capture());
        assertThat(captor.getValue().getNumbered()).isTrue();
    }

    @Test
    void desmarcarProductoComoNumerado_productoNoExiste_lanza404() {
        when(productoRepo.findByArticulo("NOEXISTE")).thenReturn(null);

        assertThatThrownBy(() -> service.desmarcarProductoComoNumerado("NOEXISTE"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void desmarcarProductoComoNumerado_conRegistrosAsociados_lanza400YNoGuarda() {
        Producto p = productoNumerado("ART001");
        when(productoRepo.findByArticulo("ART001")).thenReturn(p);
        when(numeradoRepo.existsByProducto_Articulo("ART001")).thenReturn(true);

        assertThatThrownBy(() -> service.desmarcarProductoComoNumerado("ART001"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("numerados asociados");

        verify(productoRepo, never()).save(any());
    }

    @Test
    void desmarcarProductoComoNumerado_sinRegistrosAsociados_loDesmarcaYGuarda() {
        Producto p = productoNumerado("ART001");
        when(productoRepo.findByArticulo("ART001")).thenReturn(p);
        when(numeradoRepo.existsByProducto_Articulo("ART001")).thenReturn(false);

        service.desmarcarProductoComoNumerado("ART001");

        ArgumentCaptor<Producto> captor = ArgumentCaptor.forClass(Producto.class);
        verify(productoRepo).save(captor.capture());
        assertThat(captor.getValue().getNumbered()).isFalse();
    }
}
