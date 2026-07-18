# Caché en servidor para lecturas de ClienteService Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cachear en memoria (Caffeine) las 4 operaciones de lectura de `ClienteService` para evitar que la pantalla Clientes de la app dispare una consulta a la base de datos cada vez que se abre, con invalidación total del caché en cada escritura (`createOrUpdateCliente` / `deleteCliente`).

**Architecture:** Spring Cache (`@Cacheable`/`@CacheEvict`) sobre un `CaffeineCacheManager` de proceso único (sin TTL, sin Redis — la app corre en una sola instancia). Cuatro regiones de caché, una por método de lectura, definidas como constantes en `CacheConfig`. Las escrituras evictan las 4 regiones completas (`allEntries = true`) porque una actualización puede mover un cliente de vendedor/ruta y no tenemos la key anterior sin una lectura extra.

**Tech Stack:** Spring Boot 3.5.4, `spring-boot-starter-cache`, Caffeine, JUnit 5 + Mockito + AssertJ (`spring-boot-starter-test`), Spring TestContext (`@ContextConfiguration` + `@MockitoBean`).

## Global Constraints

- Sin TTL: la única invalidación es evict en escritura (`allEntries = true` sobre las 4 regiones).
- Sin dependencias de infraestructura nueva (no Redis) — instancia única.
- `maximumSize=500` por región en el `CaffeineCacheManager`, como red de seguridad, no como mecanismo de expiración.
- No se cachean excepciones: comportamiento por defecto de Spring Cache, sin configuración adicional.
- Fuera de alcance: caché del lado Flutter, invalidación selectiva por key individual.

Spec de referencia: `docs/superpowers/specs/2026-07-18-cache-clientes-design.md`

---

## File Structure

- **Create:** `dipalza/src/main/java/cl/eos/dipalza/config/CacheConfig.java` — define el `CacheManager` (Caffeine) y las constantes de nombre de las 4 regiones.
- **Modify:** `dipalza/src/main/java/cl/eos/dipalza/service/ClienteService.java` — agrega `@Cacheable` a los 4 métodos de lectura y `@CacheEvict` a los 2 de escritura.
- **Modify:** `dipalza/pom.xml` — agrega `spring-boot-starter-cache` y `caffeine`.
- **Create:** `dipalza/src/test/java/cl/eos/dipalza/config/CacheConfigTest.java` — verifica que el `CacheManager` expone exactamente las 4 regiones esperadas.
- **Create:** `dipalza/src/test/java/cl/eos/dipalza/service/ClienteServiceCacheTest.java` — verifica, con `ClienteRepository` mockeado y un contexto Spring real (para que el proxy de caché esté activo), que las llamadas repetidas no vuelven a golpear el repositorio, y que una escritura evicta el caché.

`ClienteServiceTest.java` (unitario existente, con `@InjectMocks`, sin contexto Spring) no se modifica: las anotaciones de caché son inertes fuera de un proxy Spring, así que sigue pasando sin cambios y sigue siendo la prueba unitaria "rápida" del mapeo de datos.

---

## Task 1: Infraestructura de caché (dependencias + `CacheManager`)

**Files:**
- Modify: `dipalza/pom.xml`
- Create: `dipalza/src/main/java/cl/eos/dipalza/config/CacheConfig.java`
- Test: `dipalza/src/test/java/cl/eos/dipalza/config/CacheConfigTest.java`

**Interfaces:**
- Produces: `CacheConfig.CLIENTES_BY_VENDEDOR`, `CacheConfig.CLIENTES_BY_RUTA`, `CacheConfig.CLIENTES_BY_ID`, `CacheConfig.ALL_CLIENTES` (constantes `String`, nombres de región) — las usan las Tasks 2-4.

- [ ] **Step 1: Escribir el test que falla**

Crear `dipalza/src/test/java/cl/eos/dipalza/config/CacheConfigTest.java`:

```java
package cl.eos.dipalza.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = CacheConfig.class)
class CacheConfigTest {

    @Autowired
    CacheManager cacheManager;

    @Test
    void exponeLasCuatroRegionesDeClientes() {
        assertThat(cacheManager.getCacheNames()).containsExactlyInAnyOrder(
                CacheConfig.CLIENTES_BY_VENDEDOR,
                CacheConfig.CLIENTES_BY_RUTA,
                CacheConfig.CLIENTES_BY_ID,
                CacheConfig.ALL_CLIENTES
        );
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `cd dipalza && ./mvnw test -Dtest=CacheConfigTest`
Expected: FAIL — no compila (`CacheConfig` no existe).

- [ ] **Step 3: Agregar las dependencias en `pom.xml`**

En `dipalza/pom.xml`, inmediatamente después del bloque de `spring-boot-starter-data-jpa` (después de la línea `</dependency>` que cierra ese bloque, antes de `spring-boot-starter-security`):

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-cache</artifactId>
		</dependency>
		<dependency>
			<groupId>com.github.ben-manes.caffeine</groupId>
			<artifactId>caffeine</artifactId>
		</dependency>
```

No se fijan versiones explícitas: ambas quedan gobernadas por el BOM de `spring-boot-starter-parent` (3.5.4).

- [ ] **Step 4: Implementar `CacheConfig`**

Crear `dipalza/src/main/java/cl/eos/dipalza/config/CacheConfig.java`:

```java
package cl.eos.dipalza.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String CLIENTES_BY_VENDEDOR = "clientesByVendedor";
    public static final String CLIENTES_BY_RUTA = "clientesByRuta";
    public static final String CLIENTES_BY_ID = "clientesById";
    public static final String ALL_CLIENTES = "allClientes";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(
                CLIENTES_BY_VENDEDOR, CLIENTES_BY_RUTA, CLIENTES_BY_ID, ALL_CLIENTES
        );
        cacheManager.setCaffeine(Caffeine.newBuilder().maximumSize(500));
        return cacheManager;
    }
}
```

- [ ] **Step 5: Ejecutar el test y confirmar que pasa**

Run: `cd dipalza && ./mvnw test -Dtest=CacheConfigTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add dipalza/pom.xml dipalza/src/main/java/cl/eos/dipalza/config/CacheConfig.java dipalza/src/test/java/cl/eos/dipalza/config/CacheConfigTest.java
git commit -m "feat: agrega infraestructura de caché (Caffeine) para lecturas de clientes"
```

---

## Task 2: Cachear `getClientesByVendedor`

**Files:**
- Modify: `dipalza/src/main/java/cl/eos/dipalza/service/ClienteService.java`
- Create: `dipalza/src/test/java/cl/eos/dipalza/service/ClienteServiceCacheTest.java`

**Interfaces:**
- Consumes: `CacheConfig.CLIENTES_BY_VENDEDOR` (Task 1).
- Produces: patrón de test reutilizado por la Task 3 (contexto Spring slim con `ClienteRepository` mockeado) y por la Task 4 (evict).

- [ ] **Step 1: Escribir el test que falla**

Crear `dipalza/src/test/java/cl/eos/dipalza/service/ClienteServiceCacheTest.java`:

```java
package cl.eos.dipalza.service;

import cl.eos.dipalza.config.CacheConfig;
import cl.eos.dipalza.entity.Cliente;
import cl.eos.dipalza.entity.ids.ClienteId;
import cl.eos.dipalza.mapper.ClienteMapper;
import cl.eos.dipalza.model.ClienteDTO;
import cl.eos.dipalza.repository.ClienteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A diferencia de {@link ClienteServiceTest} (unitario puro, con {@code @InjectMocks}),
 * este test carga un contexto Spring real para que el proxy de {@code @Cacheable}/
 * {@code @CacheEvict} esté activo. Usa códigos distintos por test para que el estado
 * del caché (compartido entre tests, ya que el contexto Spring se reutiliza) no
 * contamine otros casos.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {ClienteService.class, ClienteMapper.class, CacheConfig.class})
class ClienteServiceCacheTest {

    @Autowired
    ClienteService clienteService;

    @MockitoBean
    ClienteRepository clienteRepository;

    private Cliente entidad(String rut, String codigo) {
        Cliente c = new Cliente();
        c.setId(new ClienteId(rut, codigo));
        c.setRazon("Test SA");
        return c;
    }

    @Test
    void getClientesByVendedor_segundaLlamada_noVuelveAGolpearElRepositorio() {
        String codigoVendedor = "CACHE-V01";
        when(clienteRepository.findByCodigoVendedorOrderByRazonAsc(codigoVendedor))
                .thenReturn(List.of(entidad("11111111-1", "001")));

        List<ClienteDTO> primera = clienteService.getClientesByVendedor(codigoVendedor);
        List<ClienteDTO> segunda = clienteService.getClientesByVendedor(codigoVendedor);

        assertThat(primera).hasSize(1);
        assertThat(segunda).hasSize(1);
        verify(clienteRepository, times(1)).findByCodigoVendedorOrderByRazonAsc(codigoVendedor);
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `cd dipalza && ./mvnw test -Dtest=ClienteServiceCacheTest`
Expected: FAIL — `verify(times(1))` falla porque el repositorio se invoca 2 veces (el método aún no está cacheado).

- [ ] **Step 3: Anotar `getClientesByVendedor` con `@Cacheable`**

En `dipalza/src/main/java/cl/eos/dipalza/service/ClienteService.java`, agregar los imports:

```java
import cl.eos.dipalza.config.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
```

Y anotar el método (`ClienteService.java:43-47` en el estado actual):

```java
    @Cacheable(value = CacheConfig.CLIENTES_BY_VENDEDOR, key = "#codigoVendedor")
    public List<ClienteDTO> getClientesByVendedor(String codigoVendedor) {
        return clienteRepository.findByCodigoVendedorOrderByRazonAsc(codigoVendedor).stream().map(clienteMapper::toDTO).collect(
                Collectors.toList());

    }
```

- [ ] **Step 4: Ejecutar el test y confirmar que pasa**

Run: `cd dipalza && ./mvnw test -Dtest=ClienteServiceCacheTest`
Expected: PASS

- [ ] **Step 5: Confirmar que el test unitario existente sigue pasando**

Run: `cd dipalza && ./mvnw test -Dtest=ClienteServiceTest`
Expected: PASS (sin cambios — las anotaciones de caché son inertes con `@InjectMocks`).

- [ ] **Step 6: Commit**

```bash
git add dipalza/src/main/java/cl/eos/dipalza/service/ClienteService.java dipalza/src/test/java/cl/eos/dipalza/service/ClienteServiceCacheTest.java
git commit -m "feat: cachea ClienteService.getClientesByVendedor"
```

---

## Task 3: Cachear `getClientesByRuta`, `getClienteById` y `getAllClientes`

**Files:**
- Modify: `dipalza/src/main/java/cl/eos/dipalza/service/ClienteService.java`
- Modify: `dipalza/src/test/java/cl/eos/dipalza/service/ClienteServiceCacheTest.java`

**Interfaces:**
- Consumes: `CacheConfig.CLIENTES_BY_RUTA`, `CacheConfig.CLIENTES_BY_ID`, `CacheConfig.ALL_CLIENTES` (Task 1); patrón de test de Task 2.

- [ ] **Step 1: Agregar los 3 tests que fallan**

Agregar a `ClienteServiceCacheTest` (mismo archivo, mismos imports ya presentes; agregar `import java.util.Optional;`):

```java
    @Test
    void getClientesByRuta_segundaLlamada_noVuelveAGolpearElRepositorio() {
        String ruta = "CACHE-R01";
        when(clienteRepository.getClienteByCodigoRuta(ruta))
                .thenReturn(List.of(entidad("22222222-2", "001")));

        clienteService.getClientesByRuta(ruta);
        clienteService.getClientesByRuta(ruta);

        verify(clienteRepository, times(1)).getClienteByCodigoRuta(ruta);
    }

    @Test
    void getClienteById_segundaLlamada_noVuelveAGolpearElRepositorio() {
        ClienteId id = new ClienteId("33333333-3", "001");
        when(clienteRepository.findById(id)).thenReturn(Optional.of(entidad("33333333-3", "001")));

        clienteService.getClienteById("33333333-3", "001");
        clienteService.getClienteById("33333333-3", "001");

        verify(clienteRepository, times(1)).findById(id);
    }

    @Test
    void getAllClientes_segundaLlamada_noVuelveAGolpearElRepositorio() {
        when(clienteRepository.findAll()).thenReturn(List.of(entidad("44444444-4", "001")));

        clienteService.getAllClientes();
        clienteService.getAllClientes();

        verify(clienteRepository, times(1)).findAll();
    }
```

- [ ] **Step 2: Ejecutar los tests y confirmar que fallan**

Run: `cd dipalza && ./mvnw test -Dtest=ClienteServiceCacheTest`
Expected: FAIL en los 3 tests nuevos (`times(1)` recibe 2 invocaciones).

- [ ] **Step 3: Anotar los 3 métodos con `@Cacheable`**

En `ClienteService.java`:

```java
    public List<ClienteDTO> getAllClientes() {
```
pasa a:
```java
    @Cacheable(CacheConfig.ALL_CLIENTES)
    public List<ClienteDTO> getAllClientes() {
```

```java
    public List<ClienteDTO> getClientesByRuta(String ruta) {
```
pasa a:
```java
    @Cacheable(value = CacheConfig.CLIENTES_BY_RUTA, key = "#ruta")
    public List<ClienteDTO> getClientesByRuta(String ruta) {
```

```java
    public Optional<ClienteDTO> getClienteById(String rut, String codigo) {
```
pasa a:
```java
    @Cacheable(value = CacheConfig.CLIENTES_BY_ID, key = "#rut + '|' + #codigo")
    public Optional<ClienteDTO> getClienteById(String rut, String codigo) {
```

(`getAllClientes` no lleva `key`: sin parámetros, Spring usa `SimpleKey.EMPTY` por defecto.)

- [ ] **Step 4: Ejecutar los tests y confirmar que pasan**

Run: `cd dipalza && ./mvnw test -Dtest=ClienteServiceCacheTest`
Expected: PASS (los 4 tests de la clase, incluyendo el de Task 2).

- [ ] **Step 5: Confirmar que el test unitario existente sigue pasando**

Run: `cd dipalza && ./mvnw test -Dtest=ClienteServiceTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add dipalza/src/main/java/cl/eos/dipalza/service/ClienteService.java dipalza/src/test/java/cl/eos/dipalza/service/ClienteServiceCacheTest.java
git commit -m "feat: cachea getClientesByRuta, getClienteById y getAllClientes"
```

---

## Task 4: Evict de las 4 regiones al escribir (`createOrUpdateCliente` / `deleteCliente`)

**Files:**
- Modify: `dipalza/src/main/java/cl/eos/dipalza/service/ClienteService.java`
- Modify: `dipalza/src/test/java/cl/eos/dipalza/service/ClienteServiceCacheTest.java`

**Interfaces:**
- Consumes: las 4 constantes de `CacheConfig` (Task 1), el patrón de test de las Tasks 2-3.

- [ ] **Step 1: Agregar el test que falla**

Agregar a `ClienteServiceCacheTest`:

```java
    @Test
    void createOrUpdateCliente_evictaElCacheDeClientesByVendedor() {
        String codigoVendedor = "CACHE-V02";
        when(clienteRepository.findByCodigoVendedorOrderByRazonAsc(codigoVendedor))
                .thenReturn(List.of(entidad("55555555-5", "001")));

        clienteService.getClientesByVendedor(codigoVendedor);
        clienteService.getClientesByVendedor(codigoVendedor);
        verify(clienteRepository, times(1)).findByCodigoVendedorOrderByRazonAsc(codigoVendedor);

        Cliente guardado = entidad("66666666-6", "002");
        when(clienteRepository.save(any())).thenReturn(guardado);

        ClienteDTO nuevoCliente = new ClienteDTO();
        nuevoCliente.setRut("66666666-6");
        nuevoCliente.setCodigo("002");
        nuevoCliente.setCodigoVendedor(codigoVendedor);
        clienteService.createOrUpdateCliente(nuevoCliente);

        clienteService.getClientesByVendedor(codigoVendedor);
        verify(clienteRepository, times(2)).findByCodigoVendedorOrderByRazonAsc(codigoVendedor);
    }
```

Agregar el import estático que falta: `import static org.mockito.Mockito.any;`

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `cd dipalza && ./mvnw test -Dtest=ClienteServiceCacheTest`
Expected: FAIL — `verify(times(2))` recibe 1 invocación (el caché de `getClientesByVendedor` sigue vigente tras el `createOrUpdateCliente`).

- [ ] **Step 3: Anotar `createOrUpdateCliente` y `deleteCliente` con `@CacheEvict`**

Agregar el import:

```java
import org.springframework.cache.annotation.CacheEvict;
```

En `ClienteService.java`:

```java
    public ClienteDTO createOrUpdateCliente(ClienteDTO clienteDTO) {
```
pasa a:
```java
    @CacheEvict(value = {CacheConfig.CLIENTES_BY_VENDEDOR, CacheConfig.CLIENTES_BY_RUTA,
            CacheConfig.CLIENTES_BY_ID, CacheConfig.ALL_CLIENTES}, allEntries = true)
    public ClienteDTO createOrUpdateCliente(ClienteDTO clienteDTO) {
```

```java
    public boolean deleteCliente(String rut, String codigo) {
```
pasa a:
```java
    @CacheEvict(value = {CacheConfig.CLIENTES_BY_VENDEDOR, CacheConfig.CLIENTES_BY_RUTA,
            CacheConfig.CLIENTES_BY_ID, CacheConfig.ALL_CLIENTES}, allEntries = true)
    public boolean deleteCliente(String rut, String codigo) {
```

- [ ] **Step 4: Ejecutar el test y confirmar que pasa**

Run: `cd dipalza && ./mvnw test -Dtest=ClienteServiceCacheTest`
Expected: PASS (los 5 tests de la clase).

- [ ] **Step 5: Confirmar que el test unitario existente sigue pasando**

Run: `cd dipalza && ./mvnw test -Dtest=ClienteServiceTest`
Expected: PASS

- [ ] **Step 6: Ejecutar toda la suite del módulo**

Run: `cd dipalza && ./mvnw test`
Expected: BUILD SUCCESS — ningún test existente (controllers, otros services) se ve afectado, ya que ningún otro componente depende de `ClienteService` de forma que la anotación de caché cambie su contrato público (mismas firmas, mismos tipos de retorno).

- [ ] **Step 7: Commit**

```bash
git add dipalza/src/main/java/cl/eos/dipalza/service/ClienteService.java dipalza/src/test/java/cl/eos/dipalza/service/ClienteServiceCacheTest.java
git commit -m "feat: evicta el caché de clientes en createOrUpdateCliente y deleteCliente"
```
