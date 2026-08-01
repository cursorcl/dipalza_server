# Geocodificación Inversa (Endpoint de Calle) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Nuevo endpoint `GET /api/geocodificacion/inversa?lat=&lon=` que devuelve el nombre de la calle más cercana a una coordenada, consultando Nominatim (OpenStreetMap) con cache en memoria y límite de ~1 solicitud/segundo hacia Nominatim.

**Architecture:** Primera integración HTTP saliente del backend. `GeocodificacionService.obtenerCalle(lat, lon)` está anotado `@Cacheable` (Caffeine, mismo patrón que `ClienteService`/`CacheConfig`); en cache-miss llama a Nominatim vía un bean `RestTemplate`, con un lock interno que serializa las llamadas reales para no superar ~1/seg aunque lleguen varias solicitudes HTTP en paralelo. `GeocodificacionController` redondea lat/lon a 5 decimales antes de llamar al service (así la clave de cache no varía por ruido de punto flotante) y expone el resultado como JSON.

**Tech Stack:** Spring Boot 3.5.4, Java 21, Spring Cache + Caffeine, `RestTemplate`, JUnit 5 + Mockito + AssertJ, `@WebMvcTest`/MockMvc para tests de controller.

## Global Constraints

- Endpoint: `GET /api/geocodificacion/inversa?lat={lat}&lon={lon}` → `200 OK` con body `{"calle": "..."}`.
- Nunca falla con error HTTP hacia el cliente por un problema con Nominatim: ante cualquier falla de red/timeout, devuelve `"Calle no disponible"`.
- Resolución de calle con fallback: `address.road` → `address.pedestrian` → `address.footway` → `address.cycleway` → primer segmento de `display_name` → `"Calle sin identificar"` si no hay nada usable.
- Llamadas reales a Nominatim limitadas a ~1 cada 1.1 segundos, sin importar cuántas solicitudes concurrentes lleguen al endpoint (un lock compartido en el service serializa el acceso).
- Resultados cacheados en memoria (Caffeine), sin persistencia en base de datos — coherente con los demás caches existentes del proyecto (`CacheConfig`).
- User-Agent descriptivo en las llamadas a Nominatim (exigido por su política de uso pública).
- No modificar `PosicionController`/`PosicionService` ni ningún endpoint existente.

---

### Task 1: `GeocodificacionService` — cache, rate limit y fallback de calle

**Files:**
- Create: `dipalza/src/main/java/cl/eos/dipalza/model/NominatimAddressDTO.java`
- Create: `dipalza/src/main/java/cl/eos/dipalza/model/NominatimResponseDTO.java`
- Create: `dipalza/src/main/java/cl/eos/dipalza/config/RestTemplateConfig.java`
- Modify: `dipalza/src/main/java/cl/eos/dipalza/config/CacheConfig.java`
- Create: `dipalza/src/main/java/cl/eos/dipalza/service/GeocodificacionService.java`
- Test: `dipalza/src/test/java/cl/eos/dipalza/service/GeocodificacionServiceTest.java`

**Interfaces:**
- Consumes: ninguna clase existente del proyecto (integración nueva).
- Produces: `GeocodificacionService.obtenerCalle(double lat, double lon): String` — usado por `GeocodificacionController` en la Tarea 2. Asume que quien llama ya redondeó `lat`/`lon` si quiere aprovechar el cache entre llamadas con la misma coordenada aproximada (el service no redondea, solo cachea por el valor exacto recibido).

- [ ] **Step 1: DTOs de la respuesta de Nominatim**

Crear `dipalza/src/main/java/cl/eos/dipalza/model/NominatimAddressDTO.java`:

```java
package cl.eos.dipalza.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NominatimAddressDTO(String road, String pedestrian, String footway, String cycleway) {
}
```

Crear `dipalza/src/main/java/cl/eos/dipalza/model/NominatimResponseDTO.java`:

```java
package cl.eos.dipalza.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NominatimResponseDTO(
        NominatimAddressDTO address,
        @JsonProperty("display_name") String displayName) {
}
```

(`@JsonIgnoreProperties(ignoreUnknown = true)` es necesario: la respuesta real de Nominatim trae muchos más campos que estos, como `place_id`, `licence`, `boundingbox`, etc.)

- [ ] **Step 2: Bean `RestTemplate` con timeout y User-Agent**

Crear `dipalza/src/main/java/cl/eos/dipalza/config/RestTemplateConfig.java`:

```java
package cl.eos.dipalza.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(8))
                .defaultHeader("User-Agent", "DipalzaVentas/1.0 (uso interno, geocodificacion de recorridos)")
                .build();
    }
}
```

- [ ] **Step 3: Registrar el nuevo cache en `CacheConfig`**

En `dipalza/src/main/java/cl/eos/dipalza/config/CacheConfig.java`, agregar la constante junto a las existentes:

```java
    public static final String GEOCODIFICACION_CALLE = "geocodificacionCalle";
```

Y agregarla a la lista de caches registrados:

```java
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(
                CLIENTES_BY_VENDEDOR, CLIENTES_BY_RUTA, CLIENTES_BY_ID, ALL_CLIENTES, GEOCODIFICACION_CALLE
        );
        cacheManager.setCaffeine(Caffeine.newBuilder().maximumSize(500));
        return cacheManager;
    }
```

- [ ] **Step 4: Escribir los tests de `GeocodificacionService` (fallan porque la clase no existe)**

Crear `dipalza/src/test/java/cl/eos/dipalza/service/GeocodificacionServiceTest.java`:

```java
package cl.eos.dipalza.service;

import cl.eos.dipalza.model.NominatimAddressDTO;
import cl.eos.dipalza.model.NominatimResponseDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class GeocodificacionServiceTest {

    @Mock RestTemplate restTemplate;

    @Test
    void obtenerCalle_conRoadEnAddress_devuelveRoad() {
        GeocodificacionService service = new GeocodificacionService(restTemplate);
        NominatimResponseDTO respuesta = new NominatimResponseDTO(
                new NominatimAddressDTO("Av. Errázuriz", null, null, null), "Av. Errázuriz, Valparaíso, Chile");
        when(restTemplate.getForObject(anyString(), eq(NominatimResponseDTO.class), any(), any()))
                .thenReturn(respuesta);

        String calle = service.obtenerCalle(-33.0393, -71.6273);

        assertThat(calle).isEqualTo("Av. Errázuriz");
    }

    @Test
    void obtenerCalle_sinRoad_usaFallbackPedestrian() {
        GeocodificacionService service = new GeocodificacionService(restTemplate);
        NominatimResponseDTO respuesta = new NominatimResponseDTO(
                new NominatimAddressDTO(null, "Paseo Atkinson", null, null), "Paseo Atkinson, Valparaíso, Chile");
        when(restTemplate.getForObject(anyString(), eq(NominatimResponseDTO.class), any(), any()))
                .thenReturn(respuesta);

        String calle = service.obtenerCalle(-33.04, -71.63);

        assertThat(calle).isEqualTo("Paseo Atkinson");
    }

    @Test
    void obtenerCalle_sinAddressNiRoad_usaPrimerSegmentoDeDisplayName() {
        GeocodificacionService service = new GeocodificacionService(restTemplate);
        NominatimResponseDTO respuesta = new NominatimResponseDTO(null, "Sector Rural, Región de Valparaíso, Chile");
        when(restTemplate.getForObject(anyString(), eq(NominatimResponseDTO.class), any(), any()))
                .thenReturn(respuesta);

        String calle = service.obtenerCalle(-33.5, -71.2);

        assertThat(calle).isEqualTo("Sector Rural");
    }

    @Test
    void obtenerCalle_respuestaSinNadaUsable_devuelveCalleSinIdentificar() {
        GeocodificacionService service = new GeocodificacionService(restTemplate);
        NominatimResponseDTO respuesta = new NominatimResponseDTO(null, null);
        when(restTemplate.getForObject(anyString(), eq(NominatimResponseDTO.class), any(), any()))
                .thenReturn(respuesta);

        String calle = service.obtenerCalle(-33.5, -71.2);

        assertThat(calle).isEqualTo("Calle sin identificar");
    }

    @Test
    void obtenerCalle_fallaLaLlamadaARestTemplate_devuelveCalleNoDisponible() {
        GeocodificacionService service = new GeocodificacionService(restTemplate);
        doThrow(new RuntimeException("timeout"))
                .when(restTemplate).getForObject(anyString(), eq(NominatimResponseDTO.class), any(), any());

        String calle = service.obtenerCalle(-33.5, -71.2);

        assertThat(calle).isEqualTo("Calle no disponible");
    }

    @Test
    void obtenerCalle_dosLlamadasSeguidas_esperaAlMenosUnSegundoEntreLasLlamadasReales() {
        GeocodificacionService service = new GeocodificacionService(restTemplate);
        NominatimResponseDTO respuesta = new NominatimResponseDTO(
                new NominatimAddressDTO("Calle X", null, null, null), "Calle X, Chile");
        when(restTemplate.getForObject(anyString(), eq(NominatimResponseDTO.class), any(), any()))
                .thenReturn(respuesta);

        long inicio = System.currentTimeMillis();
        service.obtenerCalle(-33.1, -71.1);
        service.obtenerCalle(-33.2, -71.2); // coordenada distinta: no debe pegarle al cache, debe esperar el turno
        long duracion = System.currentTimeMillis() - inicio;

        assertThat(duracion).isGreaterThanOrEqualTo(1000);
    }
}
```

Run: `cd dipalza && mvn -q -Dtest=GeocodificacionServiceTest test`
Expected: FAIL — no compila (`GeocodificacionService` no existe todavía).

- [ ] **Step 5: Implementar `GeocodificacionService`**

Crear `dipalza/src/main/java/cl/eos/dipalza/service/GeocodificacionService.java`:

```java
package cl.eos.dipalza.service;

import cl.eos.dipalza.config.CacheConfig;
import cl.eos.dipalza.model.NominatimAddressDTO;
import cl.eos.dipalza.model.NominatimResponseDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class GeocodificacionService {

    private static final Logger log = LoggerFactory.getLogger(GeocodificacionService.class);
    private static final String NOMINATIM_URL = "https://nominatim.openstreetmap.org/reverse?lat={lat}&lon={lon}&format=jsonv2";
    private static final long INTERVALO_MINIMO_MS = 1100;
    private static final String CALLE_SIN_IDENTIFICAR = "Calle sin identificar";
    private static final String CALLE_NO_DISPONIBLE = "Calle no disponible";

    private final RestTemplate restTemplate;
    private final Object nominatimLock = new Object();
    private long ultimaConsultaMs = 0;

    public GeocodificacionService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Cacheable(value = CacheConfig.GEOCODIFICACION_CALLE, key = "#lat + ',' + #lon")
    public String obtenerCalle(double lat, double lon) {
        try {
            esperarTurno();
            NominatimResponseDTO respuesta = restTemplate.getForObject(NOMINATIM_URL, NominatimResponseDTO.class, lat, lon);
            return extraerCalle(respuesta);
        } catch (Exception e) {
            log.warn("No se pudo geocodificar lat={} lon={}: {}", lat, lon, e.getMessage());
            return CALLE_NO_DISPONIBLE;
        }
    }

    private void esperarTurno() {
        synchronized (nominatimLock) {
            long espera = INTERVALO_MINIMO_MS - (System.currentTimeMillis() - ultimaConsultaMs);
            if (espera > 0) {
                try {
                    Thread.sleep(espera);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            ultimaConsultaMs = System.currentTimeMillis();
        }
    }

    private String extraerCalle(NominatimResponseDTO respuesta) {
        if (respuesta == null) {
            return CALLE_SIN_IDENTIFICAR;
        }
        NominatimAddressDTO address = respuesta.address();
        if (address != null) {
            if (esUtilizable(address.road())) return address.road();
            if (esUtilizable(address.pedestrian())) return address.pedestrian();
            if (esUtilizable(address.footway())) return address.footway();
            if (esUtilizable(address.cycleway())) return address.cycleway();
        }
        if (esUtilizable(respuesta.displayName())) {
            return respuesta.displayName().split(",")[0].trim();
        }
        return CALLE_SIN_IDENTIFICAR;
    }

    private boolean esUtilizable(String valor) {
        return valor != null && !valor.isBlank();
    }
}
```

- [ ] **Step 6: Correr los tests y verificar que pasan**

Run: `cd dipalza && mvn -q -Dtest=GeocodificacionServiceTest test`
Expected: PASS, 6/6 (el último test tarda ~1.1s por el rate limiter, es esperado).

- [ ] **Step 7: Commit**

```bash
cd dipalza
git add src/main/java/cl/eos/dipalza/model/NominatimAddressDTO.java \
        src/main/java/cl/eos/dipalza/model/NominatimResponseDTO.java \
        src/main/java/cl/eos/dipalza/config/RestTemplateConfig.java \
        src/main/java/cl/eos/dipalza/config/CacheConfig.java \
        src/main/java/cl/eos/dipalza/service/GeocodificacionService.java \
        src/test/java/cl/eos/dipalza/service/GeocodificacionServiceTest.java
git commit -m "feat: servicio de geocodificación inversa con cache y límite de tasa hacia Nominatim"
```

---

### Task 2: `GeocodificacionController` — endpoint HTTP

**Files:**
- Create: `dipalza/src/main/java/cl/eos/dipalza/model/GeocodificacionResponseDTO.java`
- Create: `dipalza/src/main/java/cl/eos/dipalza/controller/GeocodificacionController.java`
- Test: `dipalza/src/test/java/cl/eos/dipalza/controller/GeocodificacionControllerTest.java`

**Interfaces:**
- Consumes: `GeocodificacionService.obtenerCalle(double lat, double lon): String` (Tarea 1).
- Produces: `GET /api/geocodificacion/inversa?lat={lat}&lon={lon}` → `200 OK`, body `GeocodificacionResponseDTO` (`{"calle": "..."}`) — es el contrato que consumirá el frontend de `dipalza_web_client` (spec conjunta `docs/superpowers/specs/2026-07-31-tabla-tramos-por-calle-design.md` de ese repo).

- [ ] **Step 1: DTO de respuesta**

Crear `dipalza/src/main/java/cl/eos/dipalza/model/GeocodificacionResponseDTO.java`:

```java
package cl.eos.dipalza.model;

public record GeocodificacionResponseDTO(String calle) {
}
```

- [ ] **Step 2: Escribir el test del controller (falla porque la clase no existe)**

Crear `dipalza/src/test/java/cl/eos/dipalza/controller/GeocodificacionControllerTest.java`:

```java
package cl.eos.dipalza.controller;

import cl.eos.dipalza.service.GeocodificacionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = GeocodificacionController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
class GeocodificacionControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean GeocodificacionService service;

    @Test
    void obtenerCalle_retorna200ConLaCalleDelService() throws Exception {
        when(service.obtenerCalle(anyDouble(), anyDouble())).thenReturn("Av. Errázuriz");

        mockMvc.perform(get("/api/geocodificacion/inversa").param("lat", "-33.0393").param("lon", "-71.6273"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calle", is("Av. Errázuriz")));
    }

    @Test
    void obtenerCalle_redondeaLatYLonA5DecimalesAntesDeLlamarAlService() throws Exception {
        when(service.obtenerCalle(anyDouble(), anyDouble())).thenReturn("Calle X");

        mockMvc.perform(get("/api/geocodificacion/inversa").param("lat", "-33.039312345").param("lon", "-71.627298765"))
                .andExpect(status().isOk());

        verify(service).obtenerCalle(-33.03931, -71.6273);
    }
}
```

Run: `cd dipalza && mvn -q -Dtest=GeocodificacionControllerTest test`
Expected: FAIL — no compila (`GeocodificacionController` no existe todavía).

- [ ] **Step 3: Implementar el controller**

Crear `dipalza/src/main/java/cl/eos/dipalza/controller/GeocodificacionController.java`:

```java
package cl.eos.dipalza.controller;

import cl.eos.dipalza.model.GeocodificacionResponseDTO;
import cl.eos.dipalza.service.GeocodificacionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/geocodificacion")
public class GeocodificacionController {

    private final GeocodificacionService geocodificacionService;

    public GeocodificacionController(GeocodificacionService geocodificacionService) {
        this.geocodificacionService = geocodificacionService;
    }

    @GetMapping("/inversa")
    public ResponseEntity<GeocodificacionResponseDTO> obtenerCalle(
            @RequestParam double lat, @RequestParam double lon) {
        double latRedondeada = Math.round(lat * 100000) / 100000.0;
        double lonRedondeada = Math.round(lon * 100000) / 100000.0;
        String calle = geocodificacionService.obtenerCalle(latRedondeada, lonRedondeada);
        return ResponseEntity.ok(new GeocodificacionResponseDTO(calle));
    }
}
```

- [ ] **Step 4: Correr los tests y verificar que pasan**

Run: `cd dipalza && mvn -q -Dtest=GeocodificacionControllerTest test`
Expected: PASS, 2/2.

- [ ] **Step 5: Suite completa del módulo y commit**

Run: `cd dipalza && mvn -q test`
Expected: mismo número de fallas preexistentes que antes de este plan (si las hay — verificar contra una corrida limpia en `main` antes de empezar), ningún test nuevo en rojo.

```bash
git add src/main/java/cl/eos/dipalza/model/GeocodificacionResponseDTO.java \
        src/main/java/cl/eos/dipalza/controller/GeocodificacionController.java \
        src/test/java/cl/eos/dipalza/controller/GeocodificacionControllerTest.java
git commit -m "feat: endpoint GET /api/geocodificacion/inversa"
```
