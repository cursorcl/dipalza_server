package cl.eos.dipalza.service;

import cl.eos.dipalza.model.ProductoElegibleNumeradoDTO;
import cl.eos.dipalza.repository.NumeradoRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prueba de integración que verifica que la regla "tiene registros asociados"
 * de {@link NumeradosService#desmarcarProductoComoNumerado(String)} realmente
 * cubre TODOS los estados de {@code Numerado}, no solo Disponible/Reservado.
 *
 * <p>{@code Producto.numerados} (la colección de la entidad) está mapeada con
 * {@code @SQLRestriction("estado = 'D' OR estado = 'R'")}, que oculta
 * silenciosamente los registros en estado Vendido. Un test con mocks
 * (ver {@code NumeradosServiceTest}) no puede detectar si la implementación
 * usara por error esa colección restringida en lugar de la consulta derivada
 * {@code existsByProducto_Articulo} (que no filtra por estado) — el mock
 * pasaría igual en ambos casos. Solo una consulta real contra la BD puede
 * distinguir ambas implementaciones.</p>
 *
 * <p>Usa el producto "022" (CHORIZO P.F.), confirmado en la BD de prueba real
 * con {@code Numbered=1} y con un {@code Numerado} id=382, numero=1, en
 * estado 'V' (Vendido) ya existente. El test es puramente de lectura: no
 * inserta ni borra filas — {@code desmarcarProductoComoNumerado} lanza la
 * excepción de negocio ANTES de escribir nada cuando detecta registros
 * asociados, así que no hay estado que limpiar.</p>
 */
@SpringBootTest
@ActiveProfiles({"dev-nosec", "it"})
class NumeradosServiceIT {

    private static final String ARTICULO_CON_NUMERADO_VENDIDO = "022";

    @Autowired
    private NumeradosService numeradosService;

    @Autowired
    private NumeradoRepository numeradoRepository;

    @Test
    void existsByProductoArticulo_noSeLimitaAEstadoDisponible() {
        boolean tieneRegistros = numeradoRepository.existsByProducto_Articulo(ARTICULO_CON_NUMERADO_VENDIDO);

        assertThat(tieneRegistros)
                .as("el producto %s tiene un Numerado en estado Vendido ('V') en la BD de prueba real, " +
                        "que la consulta derivada debe detectar aunque no esté en estado Disponible/Reservado",
                        ARTICULO_CON_NUMERADO_VENDIDO)
                .isTrue();
    }

    @Test
    void desmarcarProductoComoNumerado_conNumeradoVendido_lanza400() {
        assertThatThrownBy(() -> numeradosService.desmarcarProductoComoNumerado(ARTICULO_CON_NUMERADO_VENDIDO))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("numerados asociados");
    }

    @Test
    void findProductosElegibles_marcaTieneRegistrosAsociadosParaProductoConVendido() {
        List<ProductoElegibleNumeradoDTO> elegibles = numeradosService.findProductosElegibles();

        Optional<ProductoElegibleNumeradoDTO> dto = elegibles.stream()
                .filter(d -> ARTICULO_CON_NUMERADO_VENDIDO.equals(d.codigoProducto()))
                .findFirst();

        assertThat(dto).as("el producto %s debe estar marcado numbered=true en la BD de prueba real",
                        ARTICULO_CON_NUMERADO_VENDIDO)
                .isPresent();
        assertThat(dto.get().tieneRegistrosAsociados()).isTrue();
    }
}
