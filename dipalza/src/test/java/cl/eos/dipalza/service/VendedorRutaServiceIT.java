package cl.eos.dipalza.service;

import cl.eos.dipalza.model.RutaDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueba de integración de {@link VendedorRutaService#getRutasByVendedor(String, String)}
 * contra la base de datos de prueba real (192.168.100.102), usando un proxy Hibernate
 * LAZY real para {@code VendedorRuta.ruta} en lugar de un mock.
 *
 * <p>Con OSIV deshabilitado (`open-in-view: false` en todos los perfiles), un
 * {@code getRutasByVendedor} sin {@code @Transactional} deja la sesión de Hibernate
 * cerrada antes de que {@code RutaMapper.toDTO} toque el proxy LAZY de {@code Ruta},
 * lo que produce {@code LazyInitializationException} (HTTP 500) para cualquier
 * vendedor con al menos una ruta asignada. El test unitario con mocks
 * ({@code VendedorRutaServiceTest}) no puede detectar esto porque nunca usa un proxy
 * Hibernate real.</p>
 *
 * <p>Usa vendedor codigo "001" tipo "0" y ruta codigo "001", confirmados existentes
 * en la BD de prueba real. Crea la asociación vía {@code asignarRutas} (ya
 * transaccional, funciona independientemente del bug) y limpia la asociación al
 * finalizar para no dejar estado permanente en la BD compartida.</p>
 *
 * <p>La limpieza se hace invocando {@code asignarRutas(..., List.of())} en lugar de
 * llamar directamente {@code vendedorRutaRepository.deleteByIdCodigoVendedorAndIdTipoVendedor}
 * desde el test: ese delete derivado de Spring Data requiere una transacción activa
 * para ejecutar el "remove" (ver {@code JpaQueryExecution.DeleteExecution}), y
 * llamarlo fuera de una transacción lanza {@code TransactionRequiredException} —
 * se confirmó empíricamente que anotar los métodos {@code @BeforeEach}/{@code @AfterEach}
 * con {@code @Transactional} no basta, porque el listener transaccional de Spring
 * TestContext no demarca transacciones alrededor de esos métodos de ciclo de vida.
 * {@code asignarRutas} ya es {@code @Transactional} y con lista vacía únicamente
 * borra la asociación existente, sin tocar el proxy LAZY (lista vacía -> el
 * {@code .map(...)} nunca se ejecuta), por lo que la limpieza funciona sin
 * importar si el bug de este test está corregido o no.</p>
 */
@SpringBootTest
@ActiveProfiles({"dev-nosec", "it"})
class VendedorRutaServiceIT {

    private static final String CODIGO_VENDEDOR = "001";
    private static final String TIPO_VENDEDOR = "0";
    private static final String CODIGO_RUTA = "001";

    @Autowired
    private VendedorRutaService vendedorRutaService;

    @BeforeEach
    void limpiarAsociacionPrevia() {
        // Defensivo: si una corrida previa falló antes de llegar al @AfterEach,
        // esto evita que quede estado residual que invalide el test.
        vendedorRutaService.asignarRutas(CODIGO_VENDEDOR, TIPO_VENDEDOR, List.of());
    }

    @AfterEach
    void limpiar() {
        vendedorRutaService.asignarRutas(CODIGO_VENDEDOR, TIPO_VENDEDOR, List.of());
    }

    @Test
    void getRutasByVendedor_conRutaAsignada_noLanzaLazyInitializationException() {
        vendedorRutaService.asignarRutas(CODIGO_VENDEDOR, TIPO_VENDEDOR, List.of(CODIGO_RUTA));

        List<RutaDTO> rutas = vendedorRutaService.getRutasByVendedor(CODIGO_VENDEDOR, TIPO_VENDEDOR);

        assertThat(rutas).hasSize(1);
        assertThat(rutas.get(0).getCodigo()).isEqualTo(CODIGO_RUTA);
    }

    @Test
    void asignarRutas_retornoDirecto_noContieneRutaNull() {
        // A diferencia del test anterior, aquí se asserta sobre el valor que
        // `asignarRutas` retorna directamente (el mismo que recibe el controlador
        // HTTP), no sobre una llamada externa posterior. `asignarRutas` termina
        // en un self-invocation a `getRutasByVendedor` DENTRO de su propia
        // transacción: el identity map de Hibernate devuelve ahí las mismas
        // instancias de VendedorRuta recién guardadas, así que si su asociación
        // `ruta` no queda seteada explícitamente, este assert falla con NPE
        // (RutaMapper.toDTO(null) produce un elemento null en la lista).
        List<RutaDTO> asignadas =
                vendedorRutaService.asignarRutas(CODIGO_VENDEDOR, TIPO_VENDEDOR, List.of(CODIGO_RUTA));

        assertThat(asignadas).hasSize(1);
        assertThat(asignadas.get(0).getCodigo()).isEqualTo(CODIGO_RUTA);
    }
}
