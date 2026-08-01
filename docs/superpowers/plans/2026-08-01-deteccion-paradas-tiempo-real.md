# Detección de paradas en tiempo real — Implementation Plan (Backend)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detectar paradas reales de vendedores en tiempo real (al llegar cada posición GPS), persistirlas en una tabla nueva con el nombre de calle ya resuelto vía geocodificación inversa, para que el frontend deje de recalcular/regeocodificar en cada visualización.

**Architecture:** La detección (aritmética pura: agrupar posiciones cercanas por distancia/tiempo) corre síncronamente dentro de la transacción de `PosicionService.registrarUbicacion`, usando una tabla de estado persistida (`dbo.parada_vendedor_grupo_actual`) para recordar el grupo "en curso" de cada vendedor entre llamadas HTTP sucesivas. Cuando un grupo se cierra (el vendedor se aleja) y califica (≥10 min), se inserta una fila en `dbo.parada_vendedor` con un sentinel de calle, y se dispara un evento `ParadaDetectadaEvent`. Un listener `@Async` + `@TransactionalEventListener(AFTER_COMMIT)` resuelve la calle real después del commit, sin bloquear el ACK al vendedor móvil ni arriesgar la transacción de ingesta si Nominatim falla o demora.

**Tech Stack:** Spring Boot 3.5.4, Java 21, Spring Data JPA (SQL Server), Lombok, Spring Events (`ApplicationEventPublisher` + `@TransactionalEventListener`), `@Async` + `ThreadPoolTaskExecutor`. SQL Server DDL manual versionado (sin Flyway/Liquibase, `ddl-auto: none`).

## Global Constraints

- Radio de agrupación: **100.0 metros**. Duración mínima para calificar como parada: **10 minutos** (`Duration.ofMinutes(10)`). Mismos valores que los defaults actuales de `detectarParadas()` en el frontend (`dipalza_web_client/src/app/mapa/detectar-paradas.ts`).
- Radio terrestre para Haversine: **6 371 000 metros** (mismo valor que usa Leaflet internamente en `L.latLng().distanceTo()` en el frontend) — necesario para que ambos lados midan distancias de forma consistente si algún día se comparan.
- La distancia se mide siempre contra el **primer punto del grupo** (`latitudReferencia`/`longitudReferencia`, fijo), nunca contra un centroide que se recalcula en cada punto nuevo — replica exactamente `referencia = grupoActual[0]` del algoritmo frontend.
- Al cerrar un grupo que califica, `latitud`/`longitud` de la parada persistida son el **promedio** de todos los puntos del grupo (`sumaLatitud/cantidadPuntos`, `sumaLongitud/cantidadPuntos`) — replica `grupo.reduce((suma, p) => suma + p.latitud, 0) / grupo.length` del frontend.
- Un cambio de día calendario (`LocalDateTime.toLocalDate()` del nuevo punto distinto al `dia` del grupo abierto) fuerza el cierre del grupo aunque el punto esté dentro del radio — las paradas nunca cruzan medianoche.
- Ninguna excepción de la detección de paradas (agrupamiento o geocodificación) puede impedir que se persista `Posicion`/`HistorialPosicion` ni que se notifique por WebSocket — todo el bloque de detección va envuelto en try/catch dentro de `registrarUbicacion`.
- `GeocodificacionService.obtenerCalle(lat, lon)` NUNCA lanza excepción: en fallo retorna el sentinel `"Calle no disponible"`; sin dirección utilizable retorna `"Calle sin identificar"`. El nuevo código debe tratar ambos como "no disponible", no como una calle real, y puede bloquear el hilo llamante hasta ~1.1s por el throttling interno — por eso solo se invoca desde el listener asíncrono, nunca dentro de la transacción de ingesta.
- Estilo de entidades: seguir el patrón Lombok (`@Getter @Setter @EqualsAndHashCode`) + `schema = "dbo"` de `Posicion`/`HistorialPosicion` (NO el patrón legacy sin Lombok de `Vendedor`).
- Estilo de tests: Mockito puro (`@ExtendWith(MockitoExtension.class)`, `@Mock`/`@InjectMocks`, `ArgumentCaptor`, AssertJ) — sin `@SpringBootTest`, siguiendo `PosicionServiceTest.java` como plantilla.
- Sin backfill: la detección opera solo hacia adelante desde que se aplique la migración. No se toca `dbo.historial_posicion` existente.
- No hay FK declarada en BD entre `dbo.historial_posicion`/`dbo.posicion` y `dbo.vendedor` pese a que las entidades JPA sí mapean `@ManyToOne`/`@OneToOne` — las tablas nuevas replican ese mismo patrón (sin FK física) por consistencia con el esquema existente.

---

### Task 1: Migración SQL — tablas nuevas

**Files:**
- Create: `base_de_datos/archive/migration/migration_20260801.sql`
- Modify: `base_de_datos/deploy_desde_cero/01_esquema_ventas.sql`

**Interfaces:**
- Produce: tablas `dbo.parada_vendedor` y `dbo.parada_vendedor_grupo_actual`, que las entidades JPA de la Task 2 mapean 1:1.

- [ ] **Paso 1: Crear el script de migración**

```sql
-- Agrega deteccion de paradas en tiempo real: tabla de paradas persistidas
-- (dbo.parada_vendedor, una fila por parada real ya cerrada y calificada,
-- con la calle resuelta via geocodificacion inversa) y tabla de estado del
-- grupo en curso por vendedor (dbo.parada_vendedor_grupo_actual), que
-- recuerda entre llamadas HTTP sucesivas el punto de referencia, hora de
-- inicio y acumuladores de promedio del grupo aun no cerrado.
--
-- Ver PosicionService.registrarUbicacion y el nuevo DeteccionParadaService.
--
-- No se hace backfill de datos historicos: la deteccion opera solo hacia
-- adelante desde el momento en que se aplique esta migracion.

BEGIN TRAN;

CREATE TABLE dbo.parada_vendedor (
    id             bigint IDENTITY(1,1) NOT NULL,
    vendedorId     varchar(3) COLLATE Modern_Spanish_CI_AS NOT NULL,
    vendedorCodigo varchar(3) COLLATE Modern_Spanish_CI_AS NOT NULL,
    latitud        float NOT NULL,
    longitud       float NOT NULL,
    horaInicio     datetime2(0) NOT NULL,
    horaFin        datetime2(0) NOT NULL,
    calle          varchar(255) COLLATE Modern_Spanish_CI_AS NOT NULL
        CONSTRAINT DF_parada_vendedor_calle DEFAULT 'Calle no disponible',
    CONSTRAINT pk_parada_vendedor PRIMARY KEY (id)
);

CREATE NONCLUSTERED INDEX idx_parada_vendedor_vendedor_horaInicio
    ON dbo.parada_vendedor (vendedorId, vendedorCodigo, horaInicio);

CREATE TABLE dbo.parada_vendedor_grupo_actual (
    vendedorId          varchar(3) COLLATE Modern_Spanish_CI_AS NOT NULL,
    vendedorCodigo      varchar(3) COLLATE Modern_Spanish_CI_AS NOT NULL,
    dia                 date NOT NULL,
    latitudReferencia   float NOT NULL,
    longitudReferencia  float NOT NULL,
    horaInicio          datetime2(0) NOT NULL,
    horaUltimoPunto     datetime2(0) NOT NULL,
    sumaLatitud         float NOT NULL,
    sumaLongitud        float NOT NULL,
    cantidadPuntos      int NOT NULL,
    CONSTRAINT pk_parada_vendedor_grupo_actual PRIMARY KEY (vendedorId, vendedorCodigo)
);

COMMIT TRAN;
```

- [ ] **Paso 2: Actualizar el script de despliegue desde cero**

En `base_de_datos/deploy_desde_cero/01_esquema_ventas.sql`, insertar las dos
`CREATE TABLE` de arriba (idénticas, sin el `BEGIN TRAN`/`COMMIT TRAN`) en
orden alfabético entre `dbo.ila` (línea ~72-77) y `dbo.posicion` (línea
~79-86) — "parada_vendedor" y "parada_vendedor_grupo_actual" preceden
alfabéticamente a "posicion".

En la sección `-- ---- Índices --------------------------------------------------------------`
(línea ~229), agregar junto al índice existente `idx_histotial_vendedor_fechaHora`
(línea ~233):

```sql
CREATE NONCLUSTERED INDEX idx_parada_vendedor_vendedor_horaInicio
    ON dbo.parada_vendedor (vendedorId, vendedorCodigo, horaInicio);
```

- [ ] **Paso 3: Commit**

```bash
git add base_de_datos/archive/migration/migration_20260801.sql base_de_datos/deploy_desde_cero/01_esquema_ventas.sql
git commit -m "feat: agrega tablas parada_vendedor y parada_vendedor_grupo_actual"
```

---

### Task 2: Entidades JPA, repositorios, Specification y utilidad de distancia

**Files:**
- Create: `dipalza/src/main/java/cl/eos/dipalza/entity/ParadaVendedor.java`
- Create: `dipalza/src/main/java/cl/eos/dipalza/entity/ParadaVendedorGrupoActual.java`
- Create: `dipalza/src/main/java/cl/eos/dipalza/repository/ParadaVendedorRepository.java`
- Create: `dipalza/src/main/java/cl/eos/dipalza/repository/ParadaVendedorGrupoActualRepository.java`
- Create: `dipalza/src/main/java/cl/eos/dipalza/specifications/ParadaVendedorSpecifications.java`
- Create: `dipalza/src/main/java/cl/eos/dipalza/utils/GeoUtils.java`
- Test: `dipalza/src/test/java/cl/eos/dipalza/utils/GeoUtilsTest.java`

**Interfaces:**
- Consumes: `cl.eos.dipalza.entity.ids.VendedorId` (existente, campos `codigo`/`tipo`), `cl.eos.dipalza.entity.Vendedor` (existente), `cl.eos.dipalza.specifications.PosicionFilter` (existente, record `(List<VendedorId> vendedorIds, LocalDateTime desde, LocalDateTime hasta, LocalDate dia)`).
- Produces: entidades `ParadaVendedor`/`ParadaVendedorGrupoActual` y sus repos, que la Task 3 (detección) y la Task 6 (endpoint) consumen directamente. `GeoUtils.distanciaMetros(double, double, double, double): double`, que la Task 3 consume.

- [ ] **Paso 1: Crear la entidad `ParadaVendedor`**

```java
package cl.eos.dipalza.entity;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Setter
@Getter
@EqualsAndHashCode
@Entity
@Table(name = "parada_vendedor", schema = "dbo")
public class ParadaVendedor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
            @JoinColumn(name = "vendedorId", referencedColumnName = "codigo"),
            @JoinColumn(name = "vendedorCodigo", referencedColumnName = "tipo")
    })
    private Vendedor vendedor;

    private double latitud;
    private double longitud;
    private LocalDateTime horaInicio;
    private LocalDateTime horaFin;
    private String calle;
}
```

- [ ] **Paso 2: Crear la entidad `ParadaVendedorGrupoActual`**

```java
package cl.eos.dipalza.entity;

import cl.eos.dipalza.entity.ids.VendedorId;
import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Setter
@Getter
@EqualsAndHashCode
@Entity
@Table(name = "parada_vendedor_grupo_actual", schema = "dbo")
public class ParadaVendedorGrupoActual {

    @EmbeddedId
    private VendedorId id;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumns({
            @JoinColumn(name = "vendedorId", referencedColumnName = "codigo"),
            @JoinColumn(name = "vendedorCodigo", referencedColumnName = "tipo")
    })
    private Vendedor vendedor;

    private LocalDate dia;
    private double latitudReferencia;
    private double longitudReferencia;
    private LocalDateTime horaInicio;
    private LocalDateTime horaUltimoPunto;
    private double sumaLatitud;
    private double sumaLongitud;
    private int cantidadPuntos;
}
```

- [ ] **Paso 3: Crear los repositorios**

```java
// dipalza/src/main/java/cl/eos/dipalza/repository/ParadaVendedorRepository.java
package cl.eos.dipalza.repository;

import cl.eos.dipalza.entity.ParadaVendedor;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ParadaVendedorRepository
        extends JpaRepository<ParadaVendedor, Long>, JpaSpecificationExecutor<ParadaVendedor> {

    @Override
    @EntityGraph(attributePaths = {"vendedor"})
    List<ParadaVendedor> findAll(Specification<ParadaVendedor> spec);

    @Modifying
    @Transactional
    @Query("update ParadaVendedor p set p.calle = :calle where p.id = :id")
    void actualizarCalle(@Param("id") Long id, @Param("calle") String calle);
}
```

```java
// dipalza/src/main/java/cl/eos/dipalza/repository/ParadaVendedorGrupoActualRepository.java
package cl.eos.dipalza.repository;

import cl.eos.dipalza.entity.ParadaVendedorGrupoActual;
import cl.eos.dipalza.entity.ids.VendedorId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParadaVendedorGrupoActualRepository
        extends JpaRepository<ParadaVendedorGrupoActual, VendedorId> {
}
```

- [ ] **Paso 4: Crear la Specification**

```java
package cl.eos.dipalza.specifications;

import cl.eos.dipalza.entity.ParadaVendedor;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

public class ParadaVendedorSpecifications {

    private ParadaVendedorSpecifications() {
    }

    public static Specification<ParadaVendedor> conFiltros(PosicionFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter.vendedorIds() != null && !filter.vendedorIds().isEmpty()) {
                predicates.add(root.get("vendedor").get("id").in(filter.vendedorIds()));
            }
            if (filter.desde() != null && filter.hasta() != null) {
                predicates.add(cb.between(root.get("horaInicio"), filter.desde(), filter.hasta()));
            } else if (filter.dia() != null) {
                predicates.add(cb.between(root.get("horaInicio"),
                        filter.dia().atStartOfDay(), filter.dia().atTime(LocalTime.MAX)));
            }
            query.orderBy(cb.asc(root.get("horaInicio")));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
```

(Sigue el patrón exacto de `HistorialPosicionSpecifications` — léelo primero
si tienes dudas sobre el estilo de construcción de predicados en este
repo.)

- [ ] **Paso 5: Crear `GeoUtils`**

```java
package cl.eos.dipalza.utils;

public final class GeoUtils {

    private static final double RADIO_TIERRA_METROS = 6371000.0;

    private GeoUtils() {
    }

    public static double distanciaMetros(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return RADIO_TIERRA_METROS * c;
    }
}
```

- [ ] **Paso 6: Escribir `GeoUtilsTest`**

```java
package cl.eos.dipalza.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeoUtilsTest {

    @Test
    void distanciaEntreDosPuntosConocidosEnSantiago() {
        // Plaza de Armas (-33.4372, -70.6506) a un punto ~100m al norte
        double distancia = GeoUtils.distanciaMetros(-33.4372, -70.6506, -33.4363, -70.6506);
        assertThat(distancia).isBetween(95.0, 105.0);
    }

    @Test
    void esSimetrica() {
        double d1 = GeoUtils.distanciaMetros(-33.4372, -70.6506, -33.05, -71.62);
        double d2 = GeoUtils.distanciaMetros(-33.05, -71.62, -33.4372, -70.6506);
        assertThat(d1).isEqualTo(d2);
    }

    @Test
    void distanciaCeroEntreUnPuntoYSiMismo() {
        assertThat(GeoUtils.distanciaMetros(-33.4372, -70.6506, -33.4372, -70.6506)).isZero();
    }
}
```

- [ ] **Paso 7: Ejecutar los tests y verificar que pasan**

Run: `cd dipalza && ./mvnw test -Dtest=GeoUtilsTest`
Expected: PASS (3/3)

- [ ] **Paso 8: Commit**

```bash
git add dipalza/src/main/java/cl/eos/dipalza/entity/ParadaVendedor.java \
        dipalza/src/main/java/cl/eos/dipalza/entity/ParadaVendedorGrupoActual.java \
        dipalza/src/main/java/cl/eos/dipalza/repository/ParadaVendedorRepository.java \
        dipalza/src/main/java/cl/eos/dipalza/repository/ParadaVendedorGrupoActualRepository.java \
        dipalza/src/main/java/cl/eos/dipalza/specifications/ParadaVendedorSpecifications.java \
        dipalza/src/main/java/cl/eos/dipalza/utils/GeoUtils.java \
        dipalza/src/test/java/cl/eos/dipalza/utils/GeoUtilsTest.java
git commit -m "feat: entidades, repositorios y utilidad de distancia para deteccion de paradas"
```

---

### Task 3: `DeteccionParadaService` — lógica de detección

**Files:**
- Create: `dipalza/src/main/java/cl/eos/dipalza/service/DeteccionParadaService.java`
- Create: `dipalza/src/main/java/cl/eos/dipalza/event/ParadaDetectadaEvent.java`
- Test: `dipalza/src/test/java/cl/eos/dipalza/service/DeteccionParadaServiceTest.java`

**Interfaces:**
- Consumes: `ParadaVendedorGrupoActualRepository`, `ParadaVendedorRepository` (Task 2), `GeoUtils.distanciaMetros` (Task 2), `VendedorId`/`Vendedor` (existentes), `ApplicationEventPublisher` (Spring, inyectado).
- Produces: `DeteccionParadaService.procesarNuevoPunto(VendedorId vendedorId, Vendedor vendedorRef, double lat, double lon, LocalDateTime fecha): void`, que la Task 5 invoca desde `PosicionService.registrarUbicacion`. Publica `ParadaDetectadaEvent(Long paradaId, double latitud, double longitud)`, que la Task 4 consume.

Nota importante para el implementador: `GeocodificacionService.CALLE_NO_DISPONIBLE`
hoy es `private`. Este task NO debe tocar `GeocodificacionService.java` — usa
el literal `"Calle no disponible"` directamente al sembrar el sentinel
(la Task 4 es la que expone la constante compartida; hasta entonces, el
literal duplicado es aceptable y temporal).

- [ ] **Paso 1: Crear el evento**

```java
package cl.eos.dipalza.event;

public record ParadaDetectadaEvent(Long paradaId, double latitud, double longitud) {
}
```

- [ ] **Paso 2: Escribir los tests de `DeteccionParadaService` (deben fallar primero, la clase aún no existe)**

```java
package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.ParadaVendedor;
import cl.eos.dipalza.entity.ParadaVendedorGrupoActual;
import cl.eos.dipalza.entity.Vendedor;
import cl.eos.dipalza.entity.ids.VendedorId;
import cl.eos.dipalza.event.ParadaDetectadaEvent;
import cl.eos.dipalza.repository.ParadaVendedorGrupoActualRepository;
import cl.eos.dipalza.repository.ParadaVendedorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeteccionParadaServiceTest {

    @Mock
    private ParadaVendedorGrupoActualRepository grupoActualRepository;
    @Mock
    private ParadaVendedorRepository paradaVendedorRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private DeteccionParadaService service;

    private final VendedorId vendedorId = new VendedorId("001", "V");
    private final Vendedor vendedorRef = new Vendedor(vendedorId);

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new DeteccionParadaService(grupoActualRepository, paradaVendedorRepository, eventPublisher);
    }

    @Test
    void sinGrupoPrevio_abreGrupoNuevo() {
        when(grupoActualRepository.findById(vendedorId)).thenReturn(Optional.empty());
        LocalDateTime fecha = LocalDateTime.of(2026, 8, 1, 10, 0);

        service.procesarNuevoPunto(vendedorId, vendedorRef, -33.45, -70.65, fecha);

        ArgumentCaptor<ParadaVendedorGrupoActual> captor = ArgumentCaptor.forClass(ParadaVendedorGrupoActual.class);
        verify(grupoActualRepository).save(captor.capture());
        ParadaVendedorGrupoActual grupo = captor.getValue();
        assertThat(grupo.getLatitudReferencia()).isEqualTo(-33.45);
        assertThat(grupo.getLongitudReferencia()).isEqualTo(-70.65);
        assertThat(grupo.getCantidadPuntos()).isEqualTo(1);
        assertThat(grupo.getSumaLatitud()).isEqualTo(-33.45);
        assertThat(grupo.getDia()).isEqualTo(fecha.toLocalDate());
        verifyNoInteractions(paradaVendedorRepository, eventPublisher);
    }

    @Test
    void dentroDelRadioMismoDia_extiendeGrupo() {
        ParadaVendedorGrupoActual grupo = grupoAbierto(LocalDateTime.of(2026, 8, 1, 10, 0), -33.45, -70.65);
        when(grupoActualRepository.findById(vendedorId)).thenReturn(Optional.of(grupo));

        LocalDateTime fechaNueva = LocalDateTime.of(2026, 8, 1, 10, 5);
        // ~10m de distancia, dentro del radio de 100m
        service.procesarNuevoPunto(vendedorId, vendedorRef, -33.45009, -70.65, fechaNueva);

        ArgumentCaptor<ParadaVendedorGrupoActual> captor = ArgumentCaptor.forClass(ParadaVendedorGrupoActual.class);
        verify(grupoActualRepository).save(captor.capture());
        ParadaVendedorGrupoActual actualizado = captor.getValue();
        assertThat(actualizado.getCantidadPuntos()).isEqualTo(2);
        assertThat(actualizado.getSumaLatitud()).isEqualTo(-33.45 + -33.45009);
        assertThat(actualizado.getHoraUltimoPunto()).isEqualTo(fechaNueva);
        assertThat(actualizado.getLatitudReferencia()).isEqualTo(-33.45); // referencia NO cambia
        verifyNoInteractions(paradaVendedorRepository, eventPublisher);
    }

    @Test
    void seAlejaYDuracionSuficiente_cierraYPersisteComoParada() {
        LocalDateTime inicio = LocalDateTime.of(2026, 8, 1, 10, 0);
        ParadaVendedorGrupoActual grupo = grupoAbierto(inicio, -33.45, -70.65);
        grupo.setHoraUltimoPunto(inicio.plusMinutes(12));
        grupo.setSumaLatitud(-33.45 * 3);
        grupo.setSumaLongitud(-70.65 * 3);
        grupo.setCantidadPuntos(3);
        when(grupoActualRepository.findById(vendedorId)).thenReturn(Optional.of(grupo));
        when(paradaVendedorRepository.save(any(ParadaVendedor.class)))
                .thenAnswer(inv -> { ParadaVendedor p = inv.getArgument(0); p.setId(99L); return p; });

        LocalDateTime fechaLejos = inicio.plusMinutes(13);
        service.procesarNuevoPunto(vendedorId, vendedorRef, -33.50, -70.70, fechaLejos); // >100m

        ArgumentCaptor<ParadaVendedor> paradaCaptor = ArgumentCaptor.forClass(ParadaVendedor.class);
        verify(paradaVendedorRepository).save(paradaCaptor.capture());
        ParadaVendedor persistida = paradaCaptor.getValue();
        assertThat(persistida.getLatitud()).isEqualTo(-33.45);
        assertThat(persistida.getLongitud()).isEqualTo(-70.65);
        assertThat(persistida.getHoraInicio()).isEqualTo(inicio);
        assertThat(persistida.getHoraFin()).isEqualTo(inicio.plusMinutes(12));
        assertThat(persistida.getCalle()).isEqualTo("Calle no disponible");

        verify(eventPublisher).publishEvent(new ParadaDetectadaEvent(99L, -33.45, -70.65));

        // se abre un grupo nuevo con el punto entrante como referencia
        ArgumentCaptor<ParadaVendedorGrupoActual> grupoCaptor = ArgumentCaptor.forClass(ParadaVendedorGrupoActual.class);
        verify(grupoActualRepository, times(2)).save(grupoCaptor.capture());
        ParadaVendedorGrupoActual nuevoGrupo = grupoCaptor.getAllValues().get(1);
        assertThat(nuevoGrupo.getLatitudReferencia()).isEqualTo(-33.50);
        assertThat(nuevoGrupo.getCantidadPuntos()).isEqualTo(1);
    }

    @Test
    void seAlejaPeroDuracionInsuficiente_descartaSinPersistir() {
        LocalDateTime inicio = LocalDateTime.of(2026, 8, 1, 10, 0);
        ParadaVendedorGrupoActual grupo = grupoAbierto(inicio, -33.45, -70.65);
        grupo.setHoraUltimoPunto(inicio.plusMinutes(4)); // < 10 min
        when(grupoActualRepository.findById(vendedorId)).thenReturn(Optional.of(grupo));

        service.procesarNuevoPunto(vendedorId, vendedorRef, -33.50, -70.70, inicio.plusMinutes(5));

        verifyNoInteractions(paradaVendedorRepository, eventPublisher);
        verify(grupoActualRepository, times(2)).save(any()); // igual se abre grupo nuevo
    }

    @Test
    void cambioDeDiaCalendario_fuerzaCierreAunqueNoSeHayaMovido() {
        LocalDateTime inicioAyer = LocalDateTime.of(2026, 7, 31, 23, 50);
        ParadaVendedorGrupoActual grupo = grupoAbierto(inicioAyer, -33.45, -70.65);
        grupo.setHoraUltimoPunto(inicioAyer.plusMinutes(11)); // cruza medianoche, califica por duracion
        when(grupoActualRepository.findById(vendedorId)).thenReturn(Optional.of(grupo));
        when(paradaVendedorRepository.save(any(ParadaVendedor.class)))
                .thenAnswer(inv -> { ParadaVendedor p = inv.getArgument(0); p.setId(1L); return p; });

        LocalDateTime hoyMismaUbicacion = LocalDateTime.of(2026, 8, 1, 0, 5); // MISMA lat/lon, otro dia
        service.procesarNuevoPunto(vendedorId, vendedorRef, -33.45, -70.65, hoyMismaUbicacion);

        verify(paradaVendedorRepository).save(any(ParadaVendedor.class));
        verify(eventPublisher).publishEvent(any(ParadaDetectadaEvent.class));
    }

    @Test
    void grupoAbiertoUnSoloPunto_alCerrarNoCalifica() {
        LocalDateTime fecha = LocalDateTime.of(2026, 8, 1, 10, 0);
        ParadaVendedorGrupoActual grupo = grupoAbierto(fecha, -33.45, -70.65); // 1 solo punto, duracion 0

        when(grupoActualRepository.findById(vendedorId)).thenReturn(Optional.of(grupo));

        service.procesarNuevoPunto(vendedorId, vendedorRef, -33.50, -70.70, fecha.plusSeconds(30));

        verifyNoInteractions(paradaVendedorRepository, eventPublisher);
    }

    private ParadaVendedorGrupoActual grupoAbierto(LocalDateTime horaInicio, double lat, double lon) {
        ParadaVendedorGrupoActual grupo = new ParadaVendedorGrupoActual();
        grupo.setId(vendedorId);
        grupo.setVendedor(vendedorRef);
        grupo.setDia(horaInicio.toLocalDate());
        grupo.setLatitudReferencia(lat);
        grupo.setLongitudReferencia(lon);
        grupo.setHoraInicio(horaInicio);
        grupo.setHoraUltimoPunto(horaInicio);
        grupo.setSumaLatitud(lat);
        grupo.setSumaLongitud(lon);
        grupo.setCantidadPuntos(1);
        return grupo;
    }
}
```

- [ ] **Paso 3: Ejecutar los tests y verificar que fallan (la clase no existe aún)**

Run: `cd dipalza && ./mvnw test -Dtest=DeteccionParadaServiceTest`
Expected: FAIL (compilation error, `DeteccionParadaService` no existe)

- [ ] **Paso 4: Implementar `DeteccionParadaService`**

```java
package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.ParadaVendedor;
import cl.eos.dipalza.entity.ParadaVendedorGrupoActual;
import cl.eos.dipalza.entity.Vendedor;
import cl.eos.dipalza.entity.ids.VendedorId;
import cl.eos.dipalza.event.ParadaDetectadaEvent;
import cl.eos.dipalza.repository.ParadaVendedorGrupoActualRepository;
import cl.eos.dipalza.repository.ParadaVendedorRepository;
import cl.eos.dipalza.utils.GeoUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class DeteccionParadaService {

    private static final double RADIO_METROS = 100.0;
    private static final Duration DURACION_MINIMA = Duration.ofMinutes(10);
    private static final String CALLE_PENDIENTE = "Calle no disponible";

    private final ParadaVendedorGrupoActualRepository grupoActualRepository;
    private final ParadaVendedorRepository paradaVendedorRepository;
    private final ApplicationEventPublisher eventPublisher;

    public DeteccionParadaService(ParadaVendedorGrupoActualRepository grupoActualRepository,
                                   ParadaVendedorRepository paradaVendedorRepository,
                                   ApplicationEventPublisher eventPublisher) {
        this.grupoActualRepository = grupoActualRepository;
        this.paradaVendedorRepository = paradaVendedorRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void procesarNuevoPunto(VendedorId vendedorId, Vendedor vendedorRef,
                                    double lat, double lon, LocalDateTime fecha) {
        ParadaVendedorGrupoActual grupo = grupoActualRepository.findById(vendedorId).orElse(null);

        if (grupo == null) {
            abrirNuevoGrupo(vendedorId, vendedorRef, lat, lon, fecha);
            return;
        }

        boolean cambioDeDia = !grupo.getDia().equals(fecha.toLocalDate());
        boolean seAleja = !cambioDeDia && GeoUtils.distanciaMetros(
                grupo.getLatitudReferencia(), grupo.getLongitudReferencia(), lat, lon) > RADIO_METROS;

        if (cambioDeDia || seAleja) {
            cerrarGrupo(grupo, vendedorRef);
            abrirNuevoGrupo(vendedorId, vendedorRef, lat, lon, fecha);
        } else {
            grupo.setHoraUltimoPunto(fecha);
            grupo.setSumaLatitud(grupo.getSumaLatitud() + lat);
            grupo.setSumaLongitud(grupo.getSumaLongitud() + lon);
            grupo.setCantidadPuntos(grupo.getCantidadPuntos() + 1);
            grupoActualRepository.save(grupo);
        }
    }

    private void abrirNuevoGrupo(VendedorId vendedorId, Vendedor vendedorRef,
                                  double lat, double lon, LocalDateTime fecha) {
        ParadaVendedorGrupoActual nuevo = new ParadaVendedorGrupoActual();
        nuevo.setId(vendedorId);
        nuevo.setVendedor(vendedorRef);
        nuevo.setDia(fecha.toLocalDate());
        nuevo.setLatitudReferencia(lat);
        nuevo.setLongitudReferencia(lon);
        nuevo.setHoraInicio(fecha);
        nuevo.setHoraUltimoPunto(fecha);
        nuevo.setSumaLatitud(lat);
        nuevo.setSumaLongitud(lon);
        nuevo.setCantidadPuntos(1);
        grupoActualRepository.save(nuevo);
    }

    private void cerrarGrupo(ParadaVendedorGrupoActual grupo, Vendedor vendedorRef) {
        Duration duracion = Duration.between(grupo.getHoraInicio(), grupo.getHoraUltimoPunto());
        if (duracion.compareTo(DURACION_MINIMA) < 0) {
            return;
        }
        ParadaVendedor parada = new ParadaVendedor();
        parada.setVendedor(vendedorRef);
        parada.setLatitud(grupo.getSumaLatitud() / grupo.getCantidadPuntos());
        parada.setLongitud(grupo.getSumaLongitud() / grupo.getCantidadPuntos());
        parada.setHoraInicio(grupo.getHoraInicio());
        parada.setHoraFin(grupo.getHoraUltimoPunto());
        parada.setCalle(CALLE_PENDIENTE);
        parada = paradaVendedorRepository.save(parada);
        eventPublisher.publishEvent(new ParadaDetectadaEvent(parada.getId(), parada.getLatitud(), parada.getLongitud()));
    }
}
```

- [ ] **Paso 5: Ejecutar los tests y verificar que pasan**

Run: `cd dipalza && ./mvnw test -Dtest=DeteccionParadaServiceTest`
Expected: PASS (6/6)

- [ ] **Paso 6: Commit**

```bash
git add dipalza/src/main/java/cl/eos/dipalza/service/DeteccionParadaService.java \
        dipalza/src/main/java/cl/eos/dipalza/event/ParadaDetectadaEvent.java \
        dipalza/src/test/java/cl/eos/dipalza/service/DeteccionParadaServiceTest.java
git commit -m "feat: DeteccionParadaService - deteccion de paradas por agrupamiento de posiciones"
```

---

### Task 4: Geocodificación diferida (evento → listener async post-commit)

**Files:**
- Create: `dipalza/src/main/java/cl/eos/dipalza/config/AsyncConfig.java`
- Create: `dipalza/src/main/java/cl/eos/dipalza/service/ParadaGeocodificacionListener.java`
- Modify: `dipalza/src/main/java/cl/eos/dipalza/service/GeocodificacionService.java`
- Modify: `dipalza/src/main/java/cl/eos/dipalza/service/DeteccionParadaService.java`
- Test: `dipalza/src/test/java/cl/eos/dipalza/service/ParadaGeocodificacionListenerTest.java`

**Interfaces:**
- Consumes: `ParadaDetectadaEvent` (Task 3), `GeocodificacionService.obtenerCalle(double, double): String` (existente), `ParadaVendedorRepository.actualizarCalle(Long, String)` (Task 2).
- Produces: `GeocodificacionService.CALLE_NO_DISPONIBLE` pasa a ser accesible desde el mismo paquete (`cl.eos.dipalza.service`) — quita el modificador `private` de esa única constante, sin tocar el resto de la clase.

- [ ] **Paso 1: Exponer la constante en `GeocodificacionService`**

En `dipalza/src/main/java/cl/eos/dipalza/service/GeocodificacionService.java`, cambiar:

```java
private static final String CALLE_NO_DISPONIBLE = "Calle no disponible";
```

por:

```java
static final String CALLE_NO_DISPONIBLE = "Calle no disponible";
```

(quita `private`, deja visibilidad de paquete — ningún otro cambio en el
archivo. `CALLE_SIN_IDENTIFICAR` permanece `private`, no se usa fuera de
esta clase.)

- [ ] **Paso 2: Usar la constante compartida en `DeteccionParadaService`**

En `dipalza/src/main/java/cl/eos/dipalza/service/DeteccionParadaService.java`,
reemplazar:

```java
private static final String CALLE_PENDIENTE = "Calle no disponible";
```

por:

```java
private static final String CALLE_PENDIENTE = GeocodificacionService.CALLE_NO_DISPONIBLE;
```

- [ ] **Paso 3: Crear `AsyncConfig`**

```java
package cl.eos.dipalza.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "deteccionParadaExecutor")
    public Executor deteccionParadaExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("deteccion-parada-");
        executor.initialize();
        return executor;
    }
}
```

- [ ] **Paso 4: Escribir el test del listener (debe fallar primero)**

```java
package cl.eos.dipalza.service;

import cl.eos.dipalza.event.ParadaDetectadaEvent;
import cl.eos.dipalza.repository.ParadaVendedorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ParadaGeocodificacionListenerTest {

    @Mock
    private GeocodificacionService geocodificacionService;
    @Mock
    private ParadaVendedorRepository paradaVendedorRepository;

    @org.junit.jupiter.api.Test
    void resuelveCalleYActualizaLaFila() {
        ParadaGeocodificacionListener listener =
                new ParadaGeocodificacionListener(geocodificacionService, paradaVendedorRepository);
        when(geocodificacionService.obtenerCalle(-33.45, -70.65)).thenReturn("Av. Providencia");

        listener.onParadaDetectada(new ParadaDetectadaEvent(1L, -33.45, -70.65));

        verify(paradaVendedorRepository).actualizarCalle(1L, "Av. Providencia");
    }

    @Test
    void geocodificacionFallaOSinResultado_persisteSentinelIgual() {
        ParadaGeocodificacionListener listener =
                new ParadaGeocodificacionListener(geocodificacionService, paradaVendedorRepository);
        when(geocodificacionService.obtenerCalle(-33.45, -70.65)).thenReturn("Calle no disponible");

        listener.onParadaDetectada(new ParadaDetectadaEvent(1L, -33.45, -70.65));

        verify(paradaVendedorRepository).actualizarCalle(1L, "Calle no disponible");
    }
}
```

- [ ] **Paso 5: Ejecutar los tests y verificar que fallan**

Run: `cd dipalza && ./mvnw test -Dtest=ParadaGeocodificacionListenerTest`
Expected: FAIL (compilation error, `ParadaGeocodificacionListener` no existe)

- [ ] **Paso 6: Implementar `ParadaGeocodificacionListener`**

```java
package cl.eos.dipalza.service;

import cl.eos.dipalza.event.ParadaDetectadaEvent;
import cl.eos.dipalza.repository.ParadaVendedorRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ParadaGeocodificacionListener {

    private final GeocodificacionService geocodificacionService;
    private final ParadaVendedorRepository paradaVendedorRepository;

    public ParadaGeocodificacionListener(GeocodificacionService geocodificacionService,
                                          ParadaVendedorRepository paradaVendedorRepository) {
        this.geocodificacionService = geocodificacionService;
        this.paradaVendedorRepository = paradaVendedorRepository;
    }

    @Async("deteccionParadaExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onParadaDetectada(ParadaDetectadaEvent event) {
        String calle = geocodificacionService.obtenerCalle(event.latitud(), event.longitud());
        paradaVendedorRepository.actualizarCalle(event.paradaId(), calle);
    }
}
```

- [ ] **Paso 7: Ejecutar los tests y verificar que pasan**

Run: `cd dipalza && ./mvnw test -Dtest=ParadaGeocodificacionListenerTest,DeteccionParadaServiceTest,GeocodificacionServiceTest`
Expected: PASS (todos verdes — confirma que exponer la constante no rompió `GeocodificacionServiceTest`)

- [ ] **Paso 8: Commit**

```bash
git add dipalza/src/main/java/cl/eos/dipalza/config/AsyncConfig.java \
        dipalza/src/main/java/cl/eos/dipalza/service/ParadaGeocodificacionListener.java \
        dipalza/src/main/java/cl/eos/dipalza/service/GeocodificacionService.java \
        dipalza/src/main/java/cl/eos/dipalza/service/DeteccionParadaService.java \
        dipalza/src/test/java/cl/eos/dipalza/service/ParadaGeocodificacionListenerTest.java
git commit -m "feat: resuelve la calle de una parada de forma asincrona post-commit"
```

---

### Task 5: Enganche en `PosicionService.registrarUbicacion`

**Files:**
- Modify: `dipalza/src/main/java/cl/eos/dipalza/service/PosicionService.java`
- Modify: `dipalza/src/test/java/cl/eos/dipalza/service/PosicionServiceTest.java`

**Interfaces:**
- Consumes: `DeteccionParadaService.procesarNuevoPunto(VendedorId, Vendedor, double, double, LocalDateTime)` (Task 3).

- [ ] **Paso 1: Añadir los casos de test nuevos a `PosicionServiceTest`**

Lee primero `PosicionServiceTest.java` completo para replicar su helper
`dto(String vendedorId)` y el patrón de `@Mock`/`@InjectMocks` exacto.
Añade el mock de `DeteccionParadaService` a la lista de `@Mock` (Mockito lo
inyecta automáticamente en `@InjectMocks PosicionService service` por tipo)
y agrega:

```java
@Test
void registrarUbicacion_invocaDeteccionParadaServiceConLosDatosCorrectos() {
    PosicionDTO dto = dto("001");
    when(vendedorRepository.getReferenceById(any())).thenReturn(new Vendedor());
    when(posicionRepository.findByVendedorId(any())).thenReturn(null);

    service.registrarUbicacion(dto);

    ArgumentCaptor<VendedorId> idCaptor = ArgumentCaptor.forClass(VendedorId.class);
    verify(deteccionParadaService).procesarNuevoPunto(
            idCaptor.capture(), any(Vendedor.class), eq(dto.latitud()), eq(dto.longitud()), eq(dto.fechaHora()));
    assertThat(idCaptor.getValue().getCodigo()).isEqualTo(dto.vendedorId());
}

@Test
void registrarUbicacion_siDeteccionParadaServiceFalla_igualPersisteYNotifica() {
    PosicionDTO dto = dto("001");
    when(vendedorRepository.getReferenceById(any())).thenReturn(new Vendedor());
    when(posicionRepository.findByVendedorId(any())).thenReturn(null);
    doThrow(new RuntimeException("fallo simulado"))
            .when(deteccionParadaService).procesarNuevoPunto(any(), any(), anyDouble(), anyDouble(), any());

    service.registrarUbicacion(dto);

    verify(posicionRepository).save(any());
    verify(historialRepository).save(any());
    verify(messagingTemplate).convertAndSend(eq("/topic/posiciones"), any(PosicionDTO.class));
}
```

Ajusta los nombres exactos de los mocks/campos (`vendedorRepository`,
`posicionRepository`, `historialRepository`, `messagingTemplate`) a los que
ya existan en el archivo real — no los reinventes, cópialos del test
existente.

- [ ] **Paso 2: Ejecutar los tests y verificar que fallan**

Run: `cd dipalza && ./mvnw test -Dtest=PosicionServiceTest`
Expected: FAIL (compilation error: no hay campo `deteccionParadaService`/constructor no coincide)

- [ ] **Paso 3: Modificar `PosicionService`**

Inyectar `DeteccionParadaService` por constructor y un `Logger`, y agregar
el try/catch después de `historialRepository.save(historial)`:

```java
private static final Logger log = LoggerFactory.getLogger(PosicionService.class);

private final DeteccionParadaService deteccionParadaService;

public PosicionService(PosicionRepository posicionRepository, HistorialPosicionRepository historialRepository,
                        VendedorRepository vendedorRepository, SimpMessagingTemplate messagingTemplate,
                        DeteccionParadaService deteccionParadaService) {
    this.posicionRepository = posicionRepository;
    this.historialRepository = historialRepository;
    this.vendedorRepository = vendedorRepository;
    this.messagingTemplate = messagingTemplate;
    this.deteccionParadaService = deteccionParadaService;
}
```

Y dentro de `registrarUbicacion`, después de `historialRepository.save(historial);`:

```java
try {
    deteccionParadaService.procesarNuevoPunto(vendedorId, vendedorRef, lat, lon, fecha);
} catch (Exception e) {
    log.warn("Fallo la deteccion de parada para vendedor {}: no afecta el registro de posicion", vendedorId, e);
}
```

(Agrega los imports de `Logger`/`LoggerFactory` de `org.slf4j` y de
`DeteccionParadaService`.)

- [ ] **Paso 4: Ejecutar los tests y verificar que pasan**

Run: `cd dipalza && ./mvnw test -Dtest=PosicionServiceTest`
Expected: PASS (todos, incluyendo los 2 nuevos)

- [ ] **Paso 5: Commit**

```bash
git add dipalza/src/main/java/cl/eos/dipalza/service/PosicionService.java \
        dipalza/src/test/java/cl/eos/dipalza/service/PosicionServiceTest.java
git commit -m "feat: engancha la deteccion de paradas en el pipeline de ingesta de posiciones"
```

---

### Task 6: Endpoint `POST /api/deteccion/historico`

**Files:**
- Create: `dipalza/src/main/java/cl/eos/dipalza/model/ParadaVendedorDTO.java`
- Create: `dipalza/src/main/java/cl/eos/dipalza/mapper/ParadaVendedorMapper.java`
- Create: `dipalza/src/main/java/cl/eos/dipalza/service/DeteccionService.java`
- Create: `dipalza/src/main/java/cl/eos/dipalza/controller/DeteccionController.java`
- Test: `dipalza/src/test/java/cl/eos/dipalza/service/DeteccionServiceTest.java`
- Test: `dipalza/src/test/java/cl/eos/dipalza/controller/DeteccionControllerTest.java`

**Interfaces:**
- Consumes: `ParadaVendedorRepository` + `ParadaVendedorSpecifications.conFiltros` (Task 2), `PosicionFilter` (existente).
- Produces: `POST /api/deteccion/historico` (mismo shape de request que `POST /api/posicion/historico`), respuesta `List<ParadaVendedorDTO>` — el frontend (siguiente fase, otro repo) consume este contrato exacto.

- [ ] **Paso 1: Crear el DTO**

```java
package cl.eos.dipalza.model;

import java.time.LocalDateTime;

public record ParadaVendedorDTO(
        Long id, String vendedorId, String vendedorCodigo, String vendedorNombre,
        double latitud, double longitud, String calle,
        LocalDateTime horaInicio, LocalDateTime horaFin) {
}
```

- [ ] **Paso 2: Crear el mapper**

```java
package cl.eos.dipalza.mapper;

import cl.eos.dipalza.entity.ParadaVendedor;
import cl.eos.dipalza.model.ParadaVendedorDTO;

public class ParadaVendedorMapper {

    private ParadaVendedorMapper() {
    }

    public static ParadaVendedorDTO toDTO(ParadaVendedor p) {
        if (p == null || p.getVendedor() == null) {
            return null;
        }
        return new ParadaVendedorDTO(
                p.getId(), p.getVendedor().getId().getCodigo(), p.getVendedor().getId().getTipo(),
                p.getVendedor().getNombre(), p.getLatitud(), p.getLongitud(), p.getCalle(),
                p.getHoraInicio(), p.getHoraFin());
    }
}
```

(Verifica los nombres exactos de los getters de `VendedorId`
—`getCodigo()`/`getTipo()`— leyendo `entity/ids/VendedorId.java` antes de
escribir esto; si difieren, ajusta.)

- [ ] **Paso 3: Escribir `DeteccionServiceTest` (debe fallar primero)**

```java
package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.ParadaVendedor;
import cl.eos.dipalza.entity.Vendedor;
import cl.eos.dipalza.entity.ids.VendedorId;
import cl.eos.dipalza.repository.ParadaVendedorRepository;
import cl.eos.dipalza.specifications.PosicionFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeteccionServiceTest {

    @Mock
    private ParadaVendedorRepository paradaVendedorRepository;

    @Test
    void buscarHistorico_delegaAlRepositorioYMapeaADTO() {
        DeteccionService service = new DeteccionService(paradaVendedorRepository);
        Vendedor vendedor = new Vendedor(new VendedorId("001", "V"));
        vendedor.setNombre("Juan Perez");
        ParadaVendedor parada = new ParadaVendedor();
        parada.setId(1L);
        parada.setVendedor(vendedor);
        parada.setLatitud(-33.45);
        parada.setLongitud(-70.65);
        parada.setCalle("Av. Providencia");
        parada.setHoraInicio(LocalDateTime.of(2026, 8, 1, 10, 0));
        parada.setHoraFin(LocalDateTime.of(2026, 8, 1, 10, 15));
        when(paradaVendedorRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
                .thenReturn(List.of(parada));

        List<cl.eos.dipalza.model.ParadaVendedorDTO> resultado =
                service.buscarHistorico(new PosicionFilter(List.of(vendedor.getId()), null, null, LocalDate.of(2026, 8, 1)));

        assertThat(resultado).hasSize(1);
        assertThat(resultado.get(0).calle()).isEqualTo("Av. Providencia");
        assertThat(resultado.get(0).vendedorNombre()).isEqualTo("Juan Perez");
    }
}
```

- [ ] **Paso 4: Ejecutar y verificar que falla**

Run: `cd dipalza && ./mvnw test -Dtest=DeteccionServiceTest`
Expected: FAIL (compilation error, `DeteccionService` no existe)

- [ ] **Paso 5: Implementar `DeteccionService`**

```java
package cl.eos.dipalza.service;

import cl.eos.dipalza.mapper.ParadaVendedorMapper;
import cl.eos.dipalza.model.ParadaVendedorDTO;
import cl.eos.dipalza.repository.ParadaVendedorRepository;
import cl.eos.dipalza.specifications.ParadaVendedorSpecifications;
import cl.eos.dipalza.specifications.PosicionFilter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DeteccionService {

    private final ParadaVendedorRepository paradaVendedorRepository;

    public DeteccionService(ParadaVendedorRepository paradaVendedorRepository) {
        this.paradaVendedorRepository = paradaVendedorRepository;
    }

    @Transactional(readOnly = true)
    public List<ParadaVendedorDTO> buscarHistorico(PosicionFilter filter) {
        return paradaVendedorRepository.findAll(ParadaVendedorSpecifications.conFiltros(filter))
                .stream().map(ParadaVendedorMapper::toDTO).toList();
    }
}
```

- [ ] **Paso 6: Ejecutar y verificar que pasa**

Run: `cd dipalza && ./mvnw test -Dtest=DeteccionServiceTest`
Expected: PASS

- [ ] **Paso 7: Escribir `DeteccionControllerTest` (patrón de `PosicionControllerTest` — léelo primero para copiar el setup de MockMvc)**

```java
package cl.eos.dipalza.controller;

import cl.eos.dipalza.model.ParadaVendedorDTO;
import cl.eos.dipalza.service.DeteccionService;
import cl.eos.dipalza.specifications.PosicionFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@WebMvcTest(DeteccionController.class)
class DeteccionControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private DeteccionService deteccionService;

    @Test
    void obtenerHistorico_retorna200ConLasParadas() throws Exception {
        ParadaVendedorDTO dto = new ParadaVendedorDTO(1L, "001", "V", "Juan Perez",
                -33.45, -70.65, "Av. Providencia",
                LocalDateTime.of(2026, 8, 1, 10, 0), LocalDateTime.of(2026, 8, 1, 10, 15));
        when(deteccionService.buscarHistorico(any(PosicionFilter.class))).thenReturn(List.of(dto));

        mockMvc.perform(post("/api/deteccion/historico")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PosicionFilter(null, null, null, LocalDate.of(2026, 8, 1)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].calle").value("Av. Providencia"));
    }
}
```

(Ajusta imports de configuración de seguridad si `PosicionControllerTest`
requiere alguna `@Import`/`@WithMockUser` adicional para pasar por el
filtro de seguridad en `@WebMvcTest` — cópialo de ahí si aplica.)

- [ ] **Paso 8: Ejecutar y verificar que falla**

Run: `cd dipalza && ./mvnw test -Dtest=DeteccionControllerTest`
Expected: FAIL (compilation error, `DeteccionController` no existe)

- [ ] **Paso 9: Implementar `DeteccionController`**

```java
package cl.eos.dipalza.controller;

import cl.eos.dipalza.model.ParadaVendedorDTO;
import cl.eos.dipalza.service.DeteccionService;
import cl.eos.dipalza.specifications.PosicionFilter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/deteccion")
public class DeteccionController {

    private final DeteccionService deteccionService;

    public DeteccionController(DeteccionService deteccionService) {
        this.deteccionService = deteccionService;
    }

    @PostMapping("/historico")
    public ResponseEntity<List<ParadaVendedorDTO>> obtenerHistorico(@RequestBody PosicionFilter filter) {
        return ResponseEntity.ok(deteccionService.buscarHistorico(filter));
    }
}
```

- [ ] **Paso 10: Ejecutar toda la suite del backend y verificar que pasa completa**

Run: `cd dipalza && ./mvnw test`
Expected: PASS (todos los tests, existentes + nuevos)

- [ ] **Paso 11: Commit**

```bash
git add dipalza/src/main/java/cl/eos/dipalza/model/ParadaVendedorDTO.java \
        dipalza/src/main/java/cl/eos/dipalza/mapper/ParadaVendedorMapper.java \
        dipalza/src/main/java/cl/eos/dipalza/service/DeteccionService.java \
        dipalza/src/main/java/cl/eos/dipalza/controller/DeteccionController.java \
        dipalza/src/test/java/cl/eos/dipalza/service/DeteccionServiceTest.java \
        dipalza/src/test/java/cl/eos/dipalza/controller/DeteccionControllerTest.java
git commit -m "feat: endpoint POST /api/deteccion/historico"
```
