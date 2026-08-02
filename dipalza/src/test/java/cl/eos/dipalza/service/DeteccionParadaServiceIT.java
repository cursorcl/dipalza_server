package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.Vendedor;
import cl.eos.dipalza.entity.ids.VendedorId;
import cl.eos.dipalza.repository.ParadaVendedorGrupoActualRepository;
import cl.eos.dipalza.repository.ParadaVendedorRepository;
import cl.eos.dipalza.repository.VendedorRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Prueba de integración de {@link DeteccionParadaService#procesarNuevoPunto}
 * contra la base de datos de prueba real, con un {@code ApplicationContext} de
 * Spring completo (no mocks). Verifica dos cosas que
 * {@code DeteccionParadaServiceTest} (Mockito puro) no puede probar:
 *
 * <p>1. Que {@code @Transactional(propagation = REQUIRES_NEW)} realmente aisla la
 * transacción de detección de una transacción externa simulada, usando un
 * {@code EntityManager}/proxy Hibernate real para {@code Vendedor} (no un mock) —
 * el mismo tipo de riesgo que {@link VendedorRutaServiceIT} documenta para otro
 * caso de proxies LAZY reales.</p>
 *
 * <p>2. Que el listener asíncrono de geocodificación ({@code @Async} +
 * {@code @TransactionalEventListener(AFTER_COMMIT)}) efectivamente resuelve la
 * calle real después de que la transacción de detección comitea — hace una
 * llamada real a Nominatim (red pública), por lo que usa
 * {@code Awaitility.await()} en vez de un sleep fijo.</p>
 *
 * <p>Usa vendedor código "001" tipo "0" (mismo vendedor confirmado existente que
 * usa {@link VendedorRutaServiceIT}). Limpia su propio estado antes y después
 * para no dejar filas huérfanas en {@code dbo.parada_vendedor}/
 * {@code dbo.parada_vendedor_grupo_actual} en la BD compartida — la limpieza de
 * {@code parada_vendedor} está acotada al par lat/lon fijo de prueba
 * (-33.45/-70.65), que este test es el único que usa. La tabla
 * {@code parada_vendedor_grupo_actual}, en cambio, es un acumulador de una sola
 * fila por vendedor que un GPS real (u otra corrida de este mismo test en vuelo)
 * puede estar escribiendo en cualquier momento vía
 * {@code PosicionService.registrarUbicacion}; por eso este test nunca la borra
 * a ciegas: antes de limpiar, toma una instantánea de la fila existente para ese
 * vendedor (si hay una) y, al finalizar, la restaura tal cual estaba —el peor
 * caso posible es una limpieza y restauración temporal, nunca una pérdida
 * permanente de datos reales.</p>
 */
@SpringBootTest
@ActiveProfiles({"dev-nosec", "it"})
class DeteccionParadaServiceIT {

    private static final String CODIGO_VENDEDOR = "001";
    private static final String TIPO_VENDEDOR = "0";
    private static final VendedorId VENDEDOR_ID = new VendedorId(CODIGO_VENDEDOR, TIPO_VENDEDOR);

    @Autowired
    private DeteccionParadaService deteccionParadaService;
    @Autowired
    private ParadaVendedorRepository paradaVendedorRepository;
    @Autowired
    private ParadaVendedorGrupoActualRepository grupoActualRepository;
    @Autowired
    private VendedorRepository vendedorRepository;

    private Vendedor vendedor;

    // Instantanea de la fila preexistente de parada_vendedor_grupo_actual para VENDEDOR_ID (si
    // existia una antes de que este test corriera), para restaurarla en @AfterEach y nunca perder
    // datos reales de un GPS en curso u otra corrida de este test en vuelo. Null si no habia nada.
    private cl.eos.dipalza.entity.ParadaVendedorGrupoActual grupoPreexistente;

    // Solo true cuando prepararEstado() completo exitosamente (busqueda del vendedor +
    // captura de la instantanea). Si @BeforeEach lanza antes de llegar al final, JUnit 5
    // igual ejecuta @AfterEach -- sin esta bandera, limpiar() borraria
    // parada_vendedor_grupo_actual sin haber capturado nada que restaurar, reintroduciendo
    // el riesgo de borrado a ciegas que la instantanea existe para evitar.
    private boolean estadoPreparado;

    @BeforeEach
    void prepararEstado() {
        vendedor = vendedorRepository.findById(VENDEDOR_ID).orElseThrow();
        capturarGrupoPreexistente();
        grupoActualRepository.deleteById(VENDEDOR_ID);
        limpiarParadasDePrueba();
        estadoPreparado = true;
    }

    @AfterEach
    void limpiar() {
        if (!estadoPreparado) {
            // prepararEstado() no completo (p.ej. el vendedor de prueba ya no existe) --
            // no se capturo ninguna instantanea, asi que no hay nada seguro que limpiar.
            return;
        }
        // Borra lo que haya producido ESTE test run (nunca lo que se restaura despues).
        grupoActualRepository.deleteById(VENDEDOR_ID);
        restaurarGrupoPreexistenteSiCorresponde();
        limpiarParadasDePrueba();
    }

    private void capturarGrupoPreexistente() {
        grupoPreexistente = grupoActualRepository.findById(VENDEDOR_ID).map(existente -> {
            cl.eos.dipalza.entity.ParadaVendedorGrupoActual copia =
                    new cl.eos.dipalza.entity.ParadaVendedorGrupoActual();
            copia.setId(existente.getId());
            copia.setVendedor(vendedor);
            copia.setDia(existente.getDia());
            copia.setLatitudReferencia(existente.getLatitudReferencia());
            copia.setLongitudReferencia(existente.getLongitudReferencia());
            copia.setHoraInicio(existente.getHoraInicio());
            copia.setHoraUltimoPunto(existente.getHoraUltimoPunto());
            copia.setSumaLatitud(existente.getSumaLatitud());
            copia.setSumaLongitud(existente.getSumaLongitud());
            copia.setCantidadPuntos(existente.getCantidadPuntos());
            copia.setParadaVendedorId(existente.getParadaVendedorId());
            return copia;
        }).orElse(null);
    }

    private void restaurarGrupoPreexistenteSiCorresponde() {
        if (grupoPreexistente != null) {
            grupoActualRepository.save(grupoPreexistente);
        }
    }

    // Borra cualquier parada creada por corridas previas (o la actual) de este test,
    // identificable por su lat/lon fija de prueba -- acotado y seguro, ver Javadoc de la clase.
    private void limpiarParadasDePrueba() {
        paradaVendedorRepository.findAll((root, query, cb) ->
                cb.and(
                        cb.equal(root.get("latitud"), -33.45),
                        cb.equal(root.get("longitud"), -70.65),
                        cb.equal(root.get("vendedor").get("id"), VENDEDOR_ID)))
                .forEach(p -> paradaVendedorRepository.deleteById(p.getId()));
    }

    @Test
    void procesarNuevoPunto_cruzaElUmbralYLuegoSeActualiza_contraBdYProxyHibernateReales() {
        LocalDateTime inicio = LocalDateTime.of(LocalDate.now(), java.time.LocalTime.of(9, 0));

        deteccionParadaService.procesarNuevoPunto(VENDEDOR_ID, vendedor, -33.45, -70.65, inicio);

        Optional<cl.eos.dipalza.entity.ParadaVendedorGrupoActual> grupoTrasAbrir =
                grupoActualRepository.findById(VENDEDOR_ID);
        assertThat(grupoTrasAbrir).isPresent();
        assertThat(grupoTrasAbrir.get().getParadaVendedorId()).isNull();

        // Segundo punto, 11 minutos despues, mismo lugar -- cruza el umbral de 10 min
        deteccionParadaService.procesarNuevoPunto(VENDEDOR_ID, vendedor, -33.45, -70.65, inicio.plusMinutes(11));

        Long paradaId = grupoActualRepository.findById(VENDEDOR_ID).orElseThrow().getParadaVendedorId();
        assertThat(paradaId).isNotNull();

        List<cl.eos.dipalza.entity.ParadaVendedor> paradas = paradaVendedorRepository.findAllById(List.of(paradaId));
        assertThat(paradas).hasSize(1);
        assertThat(paradas.get(0).getHoraFin()).isEqualTo(inicio.plusMinutes(11));

        // Tercer punto, 15 minutos despues -- actualiza la MISMA fila (no crea una segunda)
        deteccionParadaService.procesarNuevoPunto(VENDEDOR_ID, vendedor, -33.45, -70.65, inicio.plusMinutes(15));

        List<cl.eos.dipalza.entity.ParadaVendedor> paradasTrasActualizar =
                paradaVendedorRepository.findAllById(List.of(paradaId));
        assertThat(paradasTrasActualizar).hasSize(1);
        assertThat(paradasTrasActualizar.get(0).getHoraFin()).isEqualTo(inicio.plusMinutes(15));

        // La geocodificacion asincrona (AFTER_COMMIT + @Async, llamada real a Nominatim) resuelve
        // la calle en algun momento tras el commit de la primera transaccion que creo la parada.
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            String calle = paradaVendedorRepository.findAllById(List.of(paradaId)).get(0).getCalle();
            assertThat(calle).isNotEqualTo("Calle no disponible");
        });
    }
}
