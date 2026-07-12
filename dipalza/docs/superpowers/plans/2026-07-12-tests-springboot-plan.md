# Tests Spring Boot — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add ~128 tests to the Spring Boot backend covering services, mappers/utils, controllers, and integration — raising overall coverage to ≥75%.

**Architecture:** Three-layer approach: (1) `@ExtendWith(MockitoExtension.class)` unit tests for services and mappers; (2) `@WebMvcTest` slice tests for controllers (security excluded); (3) one `@SpringBootTest` integration test. H2 in-memory replaces SQL Server during tests.

**Tech Stack:** JUnit 5, Mockito 5, Spring Boot Test 3.5, MockMvc, H2 2.x, JaCoCo (already configured).

## Global Constraints

- All new test classes go in `src/test/java/cl/eos/dipalza/`; package mirrors the source package.
- Run commands: `mvn test -pl dipalza -Dfrontend.skip=true [-Dtest=ClassName]`
- `@WebMvcTest` pattern: `@WebMvcTest(value = XController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)` — same as existing `ProductoControllerTest`.
- No real database connections in new tests; all repos are `@Mock` or `@MockBean`.
- `@ActiveProfiles("dev-sec")` required only for `AuthControllerTest`.
- JaCoCo report at `target/site/jacoco/index.html` after `mvn test`.

---

## Task 1: Infrastructure — H2 + test properties

**Files:**
- Modify: `dipalza/pom.xml`
- Create: `src/test/resources/application-dev-sec.yml`

**Interfaces:**
- Produces: H2 in-memory DB available for all `@SpringBootTest` tests under the `dev-sec` profile.

- [ ] **Step 1: Add H2 dependency to pom.xml**

In `pom.xml`, after the `spring-boot-starter-test` dependency block (around line 94), add:

```xml
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: Create test override for dev-sec datasource**

Create file `src/test/resources/application-dev-sec.yml`:

```yaml
spring:
  config:
    activate:
      on-profile: dev-sec
  datasource:
    url: jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS dbo
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: create-drop
      naming:
        physical-strategy: org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl
        implicit-strategy: org.hibernate.boot.model.naming.ImplicitNamingStrategyLegacyJpaImpl
    properties:
      hibernate:
        dialect: org.hibernate.dialect.H2Dialect
    show-sql: false

facturacion:
  datasource:
    url: jdbc:h2:mem:facturaciondb;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    username: sa
    password: ""

security:
  jwt:
    issuer: dipalza-test
    secret: 8f1d7c0a9b52e34f67a8d5c2b19e04fa37b1e2c4f85d09ab23cd4567e90fab12
    access-minutes: 10
    refresh-hr: 5
```

- [ ] **Step 3: Compile to verify**

```bash
mvn compile -pl dipalza -Dfrontend.skip=true
```

Expected: `BUILD SUCCESS` with no compilation errors.

- [ ] **Step 4: Commit**

```bash
cd /Users/cursor/Dev/dipalza/dipalza.springboot
git add dipalza/pom.xml dipalza/src/test/resources/application-dev-sec.yml
git commit -m "test: agrega H2 y propiedades de test para perfil dev-sec"
```

---

## Task 2: Utils + VentaMapper unit tests

**Files:**
- Create: `src/test/java/cl/eos/dipalza/utils/UtilsTest.java`
- Create: `src/test/java/cl/eos/dipalza/mapper/VentaMapperTest.java`

**Interfaces:**
- Consumes: `cl.eos.dipalza.utils.Utils` (static), `cl.eos.dipalza.mapper.VentaMapper` (static)
- Produces: 9 green tests covering `putZeroesAtBegin`, `putStrAtBegin`, `VentaMapper.toVentaDTO`, `VentaMapper.toVentaDetalleDTO`.

- [ ] **Step 1: Write failing tests — UtilsTest**

Create `src/test/java/cl/eos/dipalza/utils/UtilsTest.java`:

```java
package cl.eos.dipalza.utils;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class UtilsTest {

    @Test
    void putZeroesAtBegin_ceroConLen4_retornaCuatroCeros() {
        assertThat(Utils.putZeroesAtBegin(0, 4)).isEqualTo("0000");
    }

    @Test
    void putZeroesAtBegin_numeroMenorQueLen_rellenaConCeros() {
        assertThat(Utils.putZeroesAtBegin(7, 3)).isEqualTo("007");
    }

    @Test
    void putZeroesAtBegin_numeroIgualAlLen_noAgregueCeros() {
        assertThat(Utils.putZeroesAtBegin(123, 3)).isEqualTo("123");
    }

    @Test
    void putStrAtBegin_sourceMenorQueLen_rellenaPorLaIzquierda() {
        assertThat(Utils.putStrAtBegin("AB", ' ', 5)).isEqualTo("   AB");
    }

    @Test
    void putStrAtBegin_sourceIgualAlLen_noModifica() {
        assertThat(Utils.putStrAtBegin("ABC", 'X', 3)).isEqualTo("ABC");
    }

    @Test
    void putStrAtBegin_sourceMayorQueLen_retornaOriginal() {
        assertThat(Utils.putStrAtBegin("ABCDE", 'X', 3)).isEqualTo("ABCDE");
    }
}
```

- [ ] **Step 2: Write failing tests — VentaMapperTest**

Create `src/test/java/cl/eos/dipalza/mapper/VentaMapperTest.java`:

```java
package cl.eos.dipalza.mapper;

import cl.eos.dipalza.entity.*;
import cl.eos.dipalza.entity.ids.ClienteId;
import cl.eos.dipalza.entity.ids.VendedorId;
import cl.eos.dipalza.model.venta.VentaDTO;
import cl.eos.dipalza.model.venta.VentaDetalleDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class VentaMapperTest {

    private Venta ventaMinima() {
        Venta v = new Venta();
        v.setId(1L);
        v.setFecha(LocalDate.of(2026, 1, 15));
        v.setTotalNeto(BigDecimal.valueOf(1000));
        v.setTotalIva(BigDecimal.valueOf(190));
        v.setTotalIla(BigDecimal.ZERO);
        v.setTotalDescuento(BigDecimal.ZERO);
        v.setTotal(BigDecimal.valueOf(1190));
        return v;
    }

    @Test
    void toVentaDTO_sinClienteNiVendedor_camposNulos() {
        VentaDTO dto = VentaMapper.toVentaDTO(ventaMinima());
        assertThat(dto.getId()).isEqualTo(1L);
        assertThat(dto.getRutCliente()).isNull();
        assertThat(dto.getCodigoVendedor()).isNull();
    }

    @Test
    void toVentaDTO_conCliente_mapea_rutYCodigo() {
        Venta v = ventaMinima();
        Cliente c = new Cliente();
        c.setId(new ClienteId("12345678-9", "001"));
        c.setRazon("Empresa Test");
        v.setCliente(c);

        VentaDTO dto = VentaMapper.toVentaDTO(v);
        assertThat(dto.getRutCliente()).isEqualTo("12345678-9");
        assertThat(dto.getCodigoCliente()).isEqualTo("001");
        assertThat(dto.getNombreCliente()).isEqualTo("Empresa Test");
    }

    @Test
    void toVentaDTO_conVendedor_mapea_codigoYTipo() {
        Venta v = ventaMinima();
        Vendedor vend = new Vendedor();
        vend.setId(new VendedorId("V01", "0 "));
        vend.setNombre("Juan Pérez");
        v.setVendedor(vend);

        VentaDTO dto = VentaMapper.toVentaDTO(v);
        assertThat(dto.getCodigoVendedor()).isEqualTo("V01");
        assertThat(dto.getNombreVendedor()).isEqualTo("Juan Pérez");
    }

    @Test
    void toVentaDTO_detallesInicializados_aparecenEnDTO() {
        Venta v = ventaMinima();
        CondicionVenta cond = new CondicionVenta();
        cond.setDescripcion("Contado");
        v.setCondicionVenta(cond);
        v.setEstado(EstadoVenta.OPENED);

        VentaDetalle det = new VentaDetalle();
        det.setId(10L);
        det.setCantidad(BigDecimal.ONE);
        det.setPrecioUnitario(BigDecimal.valueOf(1000));
        det.setPorcentajeDescuento(BigDecimal.ZERO);
        det.setPorcentajeIva(BigDecimal.valueOf(19));
        det.setPorcentajeIla(BigDecimal.ZERO);
        v.addDetalle(det);

        VentaDTO dto = VentaMapper.toVentaDTO(v);
        assertThat(dto.getDetalles()).isNotNull().hasSize(1);
    }

    @Test
    void toVentaDetalleDTO_sinProducto_articuloNulo() {
        VentaDetalle det = new VentaDetalle();
        det.setId(5L);
        det.setCantidad(BigDecimal.valueOf(2));
        det.setPrecioUnitario(BigDecimal.valueOf(500));
        det.setPorcentajeDescuento(BigDecimal.ZERO);
        det.setPorcentajeIva(BigDecimal.valueOf(19));
        det.setPorcentajeIla(BigDecimal.ZERO);

        VentaDetalleDTO dto = VentaMapper.toVentaDetalleDTO(det);
        assertThat(dto.getArticulo()).isNull();
    }
}
```

- [ ] **Step 3: Run tests**

```bash
mvn test -pl dipalza -Dfrontend.skip=true -Dtest="UtilsTest,VentaMapperTest"
```

Expected: `Tests run: 11, Failures: 0, Errors: 0`

- [ ] **Step 4: Commit**

```bash
cd /Users/cursor/Dev/dipalza/dipalza.springboot
git add dipalza/src/test/java/cl/eos/dipalza/utils/UtilsTest.java \
        dipalza/src/test/java/cl/eos/dipalza/mapper/VentaMapperTest.java
git commit -m "test: agrega pruebas unitarias para Utils y VentaMapper"
```

---

## Task 3: JwtService unit tests

**Files:**
- Create: `src/test/java/cl/eos/dipalza/service/JwtServiceTest.java`

**Interfaces:**
- Consumes: `JwtService` — constructor: `new JwtService()`, fields set by reflection: `secret`, `issuer`, `accessMin`, `refreshHr`.
- Produces: 8 green tests covering token generation and parsing.

- [ ] **Step 1: Write failing tests**

Create `src/test/java/cl/eos/dipalza/service/JwtServiceTest.java`:

```java
package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.AppRole;
import cl.eos.dipalza.entity.AppUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class JwtServiceTest {

    private JwtService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new JwtService();
        setField("secret", "8f1d7c0a9b52e34f67a8d5c2b19e04fa37b1e2c4f85d09ab23cd4567e90fab12");
        setField("issuer", "dipalza-test");
        setField("accessMin", 10L);
        setField("refreshHr", 5L);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = JwtService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    private AppUser usuario(String username) {
        AppRole role = new AppRole();
        role.setName("ROLE_VENDEDOR");
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setPassword("hash");
        u.setRoles(Set.of(role));
        return u;
    }

    @Test
    void generateAccess_retornaTokenNoVacio() {
        String token = service.generateAccess(usuario("jdoe"));
        assertThat(token).isNotBlank();
    }

    @Test
    void generateAccess_tokenContieneClaim_roles() {
        String token = service.generateAccess(usuario("jdoe"));
        Claims claims = service.parse(token).getPayload();
        assertThat(claims.get("roles")).isNotNull();
        assertThat(claims.get("type", String.class)).isEqualTo("ACCESS");
    }

    @Test
    void generateRefresh_tokenNoContieneRoles() {
        String token = service.generateRefresh(usuario("jdoe"));
        Claims claims = service.parse(token).getPayload();
        assertThat(claims.get("roles")).isNull();
        assertThat(claims.get("type", String.class)).isEqualTo("REFRESH");
    }

    @Test
    void extractUsername_retornaElSubject() {
        String token = service.generateAccess(usuario("ana@test.cl"));
        assertThat(service.extractUsername(token)).isEqualTo("ana@test.cl");
    }

    @Test
    void parse_tokenValido_retornaJwsConClaims() {
        String token = service.generateAccess(usuario("jdoe"));
        Jws<Claims> jws = service.parse(token);
        assertThat(jws.getPayload().getSubject()).isEqualTo("jdoe");
        assertThat(jws.getPayload().getIssuer()).isEqualTo("dipalza-test");
    }

    @Test
    void parse_tokenInvalido_lanzaJwtException() {
        assertThatThrownBy(() -> service.parse("not.a.valid.jwt"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void parse_tokenFirmadoConOtroSecret_lanzaJwtException() throws Exception {
        JwtService otro = new JwtService();
        Field f = JwtService.class.getDeclaredField("secret");
        f.setAccessible(true);
        f.set(otro, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Field fi = JwtService.class.getDeclaredField("issuer");
        fi.setAccessible(true);
        fi.set(otro, "x");
        Field fa = JwtService.class.getDeclaredField("accessMin");
        fa.setAccessible(true);
        fa.set(otro, 10L);
        Field fr = JwtService.class.getDeclaredField("refreshHr");
        fr.setAccessible(true);
        fr.set(otro, 5L);

        String tokenAjeno = otro.generateAccess(usuario("hacker"));
        assertThatThrownBy(() -> service.parse(tokenAjeno))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void generateAccess_yExtraUsername_roundtrip() {
        AppUser u = usuario("vendedor@test.cl");
        String token = service.generateAccess(u);
        assertThat(service.extractUsername(token)).isEqualTo("vendedor@test.cl");
    }
}
```

- [ ] **Step 2: Run tests**

```bash
mvn test -pl dipalza -Dfrontend.skip=true -Dtest=JwtServiceTest
```

Expected: `Tests run: 8, Failures: 0, Errors: 0`

- [ ] **Step 3: Commit**

```bash
cd /Users/cursor/Dev/dipalza/dipalza.springboot
git add dipalza/src/test/java/cl/eos/dipalza/service/JwtServiceTest.java
git commit -m "test: agrega pruebas unitarias para JwtService"
```

---

## Task 4: CondicionVentaService + ConduccionService + IlaService unit tests

**Files:**
- Create: `src/test/java/cl/eos/dipalza/service/CondicionVentaServiceTest.java`
- Create: `src/test/java/cl/eos/dipalza/service/ConduccionServiceTest.java`
- Create: `src/test/java/cl/eos/dipalza/service/IlaServiceTest.java`

**Interfaces:**
- Consumes: `CondicionVentaService(@Autowired repo, mapper)`, `ConduccionService(@Autowired repo, mapper)`, `IlaService(@Autowired repo, mapper)`.
- Produces: 12 green tests (4 per service).

**Important:** These services use `@Autowired` for repo and mapper — not constructor injection. Use `@InjectMocks` + `@Mock` which handles field injection through Mockito.

- [ ] **Step 1: Write failing tests — CondicionVentaServiceTest**

Create `src/test/java/cl/eos/dipalza/service/CondicionVentaServiceTest.java`:

```java
package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.CondicionVenta;
import cl.eos.dipalza.mapper.CondicionVentaMapper;
import cl.eos.dipalza.model.CondicionVentaDTO;
import cl.eos.dipalza.repository.CondicionVentaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CondicionVentaServiceTest {

    @Mock CondicionVentaRepository repo;
    @Mock CondicionVentaMapper mapper;
    @InjectMocks CondicionVentaService service;

    private CondicionVenta entidad(String codigo) {
        CondicionVenta e = new CondicionVenta();
        e.setCodigo(codigo);
        e.setDescripcion("Contado");
        return e;
    }

    private CondicionVentaDTO dto(String codigo) {
        CondicionVentaDTO d = new CondicionVentaDTO();
        d.setCodigo(codigo);
        d.setDescripcion("Contado");
        return d;
    }

    @Test
    void getAllCondicionVentas_retornaListaMapeada() {
        when(repo.findAll()).thenReturn(List.of(entidad("01")));
        when(mapper.toDTO(any())).thenReturn(dto("01"));

        List<CondicionVentaDTO> result = service.getAllCondicionVentas();
        assertThat(result).hasSize(1).first().extracting(CondicionVentaDTO::getCodigo).isEqualTo("01");
    }

    @Test
    void getCondicionVentaById_existente_retornaDTO() {
        when(repo.findById("01")).thenReturn(Optional.of(entidad("01")));
        when(mapper.toDTO(any())).thenReturn(dto("01"));

        assertThat(service.getCondicionVentaById("01")).isPresent()
                .get().extracting(CondicionVentaDTO::getCodigo).isEqualTo("01");
    }

    @Test
    void getCondicionVentaById_noExiste_retornaEmpty() {
        when(repo.findById("99")).thenReturn(Optional.empty());
        assertThat(service.getCondicionVentaById("99")).isEmpty();
    }

    @Test
    void deleteCondicionVenta_existente_retornaTrue() {
        when(repo.existsById("01")).thenReturn(true);
        assertThat(service.deleteCondicionVenta("01")).isTrue();
        verify(repo).deleteById("01");
    }
}
```

- [ ] **Step 2: Write failing tests — ConduccionServiceTest**

Create `src/test/java/cl/eos/dipalza/service/ConduccionServiceTest.java`:

```java
package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.Conduccion;
import cl.eos.dipalza.mapper.ConduccionMapper;
import cl.eos.dipalza.model.ConduccionDTO;
import cl.eos.dipalza.repository.ConduccionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConduccionServiceTest {

    @Mock ConduccionRepository repo;
    @Mock ConduccionMapper mapper;
    @InjectMocks ConduccionService service;

    private Conduccion entidad(String codigo) {
        Conduccion e = new Conduccion();
        e.setCodigo(codigo);
        e.setDescripcion("Camión");
        return e;
    }

    private ConduccionDTO dto(String codigo) {
        ConduccionDTO d = new ConduccionDTO();
        d.setCodigo(codigo);
        d.setDescripcion("Camión");
        d.setValor(BigDecimal.valueOf(1500));
        return d;
    }

    @Test
    void getAllConduccions_retornaListaMapeada() {
        when(repo.findAll()).thenReturn(List.of(entidad("C1")));
        when(mapper.toDTO(any())).thenReturn(dto("C1"));

        List<ConduccionDTO> result = service.getAllConduccions();
        assertThat(result).hasSize(1);
    }

    @Test
    void getConduccionById_existente_retornaDTO() {
        when(repo.findById("C1")).thenReturn(Optional.of(entidad("C1")));
        when(mapper.toDTO(any())).thenReturn(dto("C1"));

        assertThat(service.getConduccionById("C1")).isPresent();
    }

    @Test
    void getConduccionById_noExiste_retornaEmpty() {
        when(repo.findById("XX")).thenReturn(Optional.empty());
        assertThat(service.getConduccionById("XX")).isEmpty();
    }

    @Test
    void deleteConduccion_noExiste_retornaFalse() {
        when(repo.existsById("XX")).thenReturn(false);
        assertThat(service.deleteConduccion("XX")).isFalse();
        verify(repo, never()).deleteById(any());
    }
}
```

- [ ] **Step 3: Write failing tests — IlaServiceTest**

Create `src/test/java/cl/eos/dipalza/service/IlaServiceTest.java`:

```java
package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.Ila;
import cl.eos.dipalza.mapper.IlaMapper;
import cl.eos.dipalza.model.IlaDTO;
import cl.eos.dipalza.repository.IlaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IlaServiceTest {

    @Mock IlaRepository repo;
    @Mock IlaMapper mapper;
    @InjectMocks IlaService service;

    private Ila entidad(String codigo) {
        Ila e = new Ila();
        e.setCodigo(codigo);
        e.setDescripcion("Bebidas alcohólicas");
        return e;
    }

    private IlaDTO dto(String codigo) {
        IlaDTO d = new IlaDTO();
        d.setCodigo(codigo);
        d.setDescripcion("Bebidas alcohólicas");
        d.setValor(BigDecimal.valueOf(27));
        return d;
    }

    @Test
    void findAllByOrderByDescripcionAsc_retornaListaMapeada() {
        when(repo.findAllByOrderByDescripcionAsc()).thenReturn(List.of(entidad("I1")));
        when(mapper.toDTO(any())).thenReturn(dto("I1"));

        List<IlaDTO> result = service.findAllByOrderByDescripcionAsc();
        assertThat(result).hasSize(1).first().extracting(IlaDTO::getCodigo).isEqualTo("I1");
    }

    @Test
    void getIlaById_existente_retornaDTO() {
        when(repo.findById("I1")).thenReturn(Optional.of(entidad("I1")));
        when(mapper.toDTO(any())).thenReturn(dto("I1"));

        assertThat(service.getIlaById("I1")).isPresent();
    }

    @Test
    void getIlaById_noExiste_retornaEmpty() {
        when(repo.findById("XX")).thenReturn(Optional.empty());
        assertThat(service.getIlaById("XX")).isEmpty();
    }

    @Test
    void deleteIla_existente_retornaTrue() {
        when(repo.existsById("I1")).thenReturn(true);
        assertThat(service.deleteIla("I1")).isTrue();
        verify(repo).deleteById("I1");
    }
}
```

- [ ] **Step 4: Run tests**

```bash
mvn test -pl dipalza -Dfrontend.skip=true \
  -Dtest="CondicionVentaServiceTest,ConduccionServiceTest,IlaServiceTest"
```

Expected: `Tests run: 12, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
cd /Users/cursor/Dev/dipalza/dipalza.springboot
git add dipalza/src/test/java/cl/eos/dipalza/service/CondicionVentaServiceTest.java \
        dipalza/src/test/java/cl/eos/dipalza/service/ConduccionServiceTest.java \
        dipalza/src/test/java/cl/eos/dipalza/service/IlaServiceTest.java
git commit -m "test: agrega pruebas unitarias para CondicionVenta, Conduccion e Ila services"
```

---

## Task 5: ClienteService + ProductoService unit tests

**Files:**
- Create: `src/test/java/cl/eos/dipalza/service/ClienteServiceTest.java`
- Create: `src/test/java/cl/eos/dipalza/service/ProductoServiceTest.java`

**Interfaces:**
- Consumes: `ClienteService` (field-injected repo + mapper), `ProductoService` (field-injected repo + mapper).
- Produces: 14 green tests.

- [ ] **Step 1: Write failing tests — ClienteServiceTest**

Create `src/test/java/cl/eos/dipalza/service/ClienteServiceTest.java`:

```java
package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.Cliente;
import cl.eos.dipalza.entity.ids.ClienteId;
import cl.eos.dipalza.mapper.ClienteMapper;
import cl.eos.dipalza.model.ClienteDTO;
import cl.eos.dipalza.repository.ClienteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClienteServiceTest {

    @Mock ClienteRepository repo;
    @Mock ClienteMapper mapper;
    @InjectMocks ClienteService service;

    private Cliente entidad(String rut, String codigo) {
        Cliente c = new Cliente();
        c.setId(new ClienteId(rut, codigo));
        c.setRazon("Test SA");
        return c;
    }

    private ClienteDTO dto(String rut, String codigo) {
        ClienteDTO d = new ClienteDTO();
        d.setRut(rut);
        d.setCodigo(codigo);
        d.setRazon("Test SA");
        return d;
    }

    @Test
    void getAllClientes_retornaListaMapeada() {
        when(repo.findAll()).thenReturn(List.of(entidad("11111111-1", "001")));
        when(mapper.toDTO(any())).thenReturn(dto("11111111-1", "001"));

        assertThat(service.getAllClientes()).hasSize(1);
    }

    @Test
    void getClientesByRuta_retornaClientesDeLaRuta() {
        when(repo.getClienteByCodigoRuta("R01")).thenReturn(List.of(entidad("11111111-1", "001")));
        when(mapper.toDTO(any())).thenReturn(dto("11111111-1", "001"));

        assertThat(service.getClientesByRuta("R01")).hasSize(1);
    }

    @Test
    void getClienteById_existente_retornaDTO() {
        when(repo.findById(new ClienteId("11111111-1", "001"))).thenReturn(Optional.of(entidad("11111111-1", "001")));
        when(mapper.toDTO(any())).thenReturn(dto("11111111-1", "001"));

        assertThat(service.getClienteById("11111111-1", "001")).isPresent();
    }

    @Test
    void getClienteById_noExiste_retornaEmpty() {
        when(repo.findById(new ClienteId("99999999-9", "001"))).thenReturn(Optional.empty());
        assertThat(service.getClienteById("99999999-9", "001")).isEmpty();
    }

    @Test
    void getClientesByVendedor_retornaListaFiltrada() {
        when(repo.findByCodigoVendedorOrderByRazonAsc("V01")).thenReturn(List.of(entidad("11111111-1", "001")));
        when(mapper.toDTO(any())).thenReturn(dto("11111111-1", "001"));

        assertThat(service.getClientesByVendedor("V01")).hasSize(1);
    }

    @Test
    void createOrUpdateCliente_guarda_yRetornaDTO() {
        Cliente saved = entidad("11111111-1", "001");
        when(mapper.toEntity(any())).thenReturn(saved);
        when(repo.save(saved)).thenReturn(saved);
        when(mapper.toDTO(saved)).thenReturn(dto("11111111-1", "001"));

        ClienteDTO result = service.createOrUpdateCliente(dto("11111111-1", "001"));
        assertThat(result.getRut()).isEqualTo("11111111-1");
    }

    @Test
    void deleteCliente_existente_retornaTrue() {
        when(repo.existsById(new ClienteId("11111111-1", "001"))).thenReturn(true);
        assertThat(service.deleteCliente("11111111-1", "001")).isTrue();
        verify(repo).deleteById(new ClienteId("11111111-1", "001"));
    }

    @Test
    void deleteCliente_noExiste_retornaFalse() {
        when(repo.existsById(new ClienteId("99999999-9", "001"))).thenReturn(false);
        assertThat(service.deleteCliente("99999999-9", "001")).isFalse();
    }
}
```

- [ ] **Step 2: Write failing tests — ProductoServiceTest**

Create `src/test/java/cl/eos/dipalza/service/ProductoServiceTest.java`:

```java
package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.Producto;
import cl.eos.dipalza.mapper.ProductoMapper;
import cl.eos.dipalza.model.ProductoDTO;
import cl.eos.dipalza.repository.ProductoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductoServiceTest {

    @Mock ProductoRepository repo;
    @Mock ProductoMapper mapper;
    @InjectMocks ProductoService service;

    private Producto entidad(String articulo) {
        Producto p = new Producto();
        p.setArticulo(articulo);
        p.setDescripcion("Queso Gouda");
        return p;
    }

    private ProductoDTO dto(String articulo) {
        ProductoDTO d = new ProductoDTO();
        d.setArticulo(articulo);
        d.setDescripcion("Queso Gouda");
        d.setVentaNeto(BigDecimal.valueOf(5000));
        return d;
    }

    @Test
    void getAllProductos_retornaListaMapeada() {
        when(repo.findAll()).thenReturn(List.of(entidad("ART001")));
        when(mapper.toDTO(any())).thenReturn(dto("ART001"));

        assertThat(service.getAllProductos()).hasSize(1);
    }

    @Test
    void findProductoById_existente_retornaDTO() {
        when(repo.findById("ART001")).thenReturn(Optional.of(entidad("ART001")));
        when(mapper.toDTO(any())).thenReturn(dto("ART001"));

        assertThat(service.findProductoById("ART001")).isPresent();
    }

    @Test
    void findProductoById_noExiste_retornaEmpty() {
        when(repo.findById("XX")).thenReturn(Optional.empty());
        assertThat(service.findProductoById("XX")).isEmpty();
    }

    @Test
    void createOrUpdateProducto_guarda_yRetornaDTO() {
        Producto saved = entidad("ART001");
        when(mapper.toEntity(any())).thenReturn(saved);
        when(repo.save(saved)).thenReturn(saved);
        when(mapper.toDTO(saved)).thenReturn(dto("ART001"));

        ProductoDTO result = service.createOrUpdateProducto(dto("ART001"));
        assertThat(result.getArticulo()).isEqualTo("ART001");
    }

    @Test
    void deleteProducto_existente_retornaTrue() {
        when(repo.existsById("ART001")).thenReturn(true);
        assertThat(service.deleteProducto("ART001")).isTrue();
    }

    @Test
    void deleteProducto_noExiste_retornaFalse() {
        when(repo.existsById("XX")).thenReturn(false);
        assertThat(service.deleteProducto("XX")).isFalse();
    }
}
```

- [ ] **Step 3: Run tests**

```bash
mvn test -pl dipalza -Dfrontend.skip=true -Dtest="ClienteServiceTest,ProductoServiceTest"
```

Expected: `Tests run: 14, Failures: 0, Errors: 0`

- [ ] **Step 4: Commit**

```bash
cd /Users/cursor/Dev/dipalza/dipalza.springboot
git add dipalza/src/test/java/cl/eos/dipalza/service/ClienteServiceTest.java \
        dipalza/src/test/java/cl/eos/dipalza/service/ProductoServiceTest.java
git commit -m "test: agrega pruebas unitarias para ClienteService y ProductoService"
```

---

## Task 6: NumeradosService unit tests

**Files:**
- Create: `src/test/java/cl/eos/dipalza/service/NumeradosServiceTest.java`

**Interfaces:**
- Consumes: `NumeradosService(NumeradoRepository, ProductoRepository, NumeradoMapper)` — constructor injection.
- Produces: 8 green tests covering `findAll`, `findByProducto`, `findById`, `save`, `deleteById`, `findPrecioPromedio`.

- [ ] **Step 1: Write failing tests**

Create `src/test/java/cl/eos/dipalza/service/NumeradosServiceTest.java`:

```java
package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.Numerado;
import cl.eos.dipalza.entity.Producto;
import cl.eos.dipalza.mapper.NumeradoMapper;
import cl.eos.dipalza.model.NumeradoDTO;
import cl.eos.dipalza.repository.NumeradoRepository;
import cl.eos.dipalza.repository.ProductoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
        n.setProducto(p);
        return n;
    }

    private NumeradoDTO dto(Long id) {
        NumeradoDTO d = new NumeradoDTO();
        d.setId(id);
        d.setCodigoProducto("ART001");
        d.setNumero(1);
        d.setPeso(BigDecimal.valueOf(10));
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
    void save_productoExiste_guardaYRetornaDTO() {
        Producto prod = new Producto();
        prod.setArticulo("ART001");
        when(productoRepo.findByArticulo("ART001")).thenReturn(prod);
        when(numeradoRepo.findById(any())).thenReturn(Optional.empty());
        Numerado saved = numerado(1L, "ART001", BigDecimal.TEN);
        when(numeradoRepo.save(any())).thenReturn(saved);
        when(mapper.toDTO(saved)).thenReturn(dto(1L));

        NumeradoDTO result = service.save(dto(null));
        assertThat(result).isNotNull();
    }

    @Test
    void deleteById_llamaAlRepo() {
        service.deleteById(5L);
        verify(numeradoRepo).deleteById(5L);
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
}
```

- [ ] **Step 2: Run tests**

```bash
mvn test -pl dipalza -Dfrontend.skip=true -Dtest=NumeradosServiceTest
```

Expected: `Tests run: 9, Failures: 0, Errors: 0`

- [ ] **Step 3: Commit**

```bash
cd /Users/cursor/Dev/dipalza/dipalza.springboot
git add dipalza/src/test/java/cl/eos/dipalza/service/NumeradosServiceTest.java
git commit -m "test: agrega pruebas unitarias para NumeradosService"
```

---

## Task 7: RutaService unit tests

**Files:**
- Create: `src/test/java/cl/eos/dipalza/service/RutaServiceTest.java`

**Interfaces:**
- Consumes: `RutaService(RutaRepository, RutaMapper, ConduccionRepository)` — constructor injection.
- Produces: 5 green tests covering `getAllRutas`, `getRutaById`, `createOrUpdateRuta` con y sin match de conducción.

- [ ] **Step 1: Write failing tests**

Create `src/test/java/cl/eos/dipalza/service/RutaServiceTest.java`:

```java
package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.Conduccion;
import cl.eos.dipalza.entity.Ruta;
import cl.eos.dipalza.mapper.RutaMapper;
import cl.eos.dipalza.model.RutaDTO;
import cl.eos.dipalza.repository.ConduccionRepository;
import cl.eos.dipalza.repository.RutaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RutaServiceTest {

    @Mock RutaRepository rutaRepo;
    @Mock RutaMapper rutaMapper;
    @Mock ConduccionRepository conduccionRepo;
    @InjectMocks RutaService service;

    private Conduccion conduccion(String codigo) {
        Conduccion c = new Conduccion();
        c.setCodigo(codigo);
        c.setDescripcion("Camión " + codigo);
        return c;
    }

    private RutaDTO dto(String codigo, String codigoConduccion) {
        RutaDTO d = new RutaDTO();
        d.setCodigo(codigo);
        d.setDescripcion("Ruta " + codigo);
        d.setCodigoConduccion(codigoConduccion);
        return d;
    }

    @Test
    void getAllRutas_retornaListaMapeada() {
        Ruta r = new Ruta();
        when(rutaRepo.findAll()).thenReturn(List.of(r));
        when(rutaMapper.toDTO(r)).thenReturn(dto("R01", "C1"));

        assertThat(service.getAllRutas()).hasSize(1);
    }

    @Test
    void getRutaById_existente_retornaDTO() {
        Ruta r = new Ruta();
        when(rutaRepo.findById("R01")).thenReturn(Optional.of(r));
        when(rutaMapper.toDTO(r)).thenReturn(dto("R01", "C1"));

        assertThat(service.getRutaById("R01")).isPresent();
    }

    @Test
    void getRutaById_noExiste_retornaEmpty() {
        when(rutaRepo.findById("XX")).thenReturn(Optional.empty());
        assertThat(service.getRutaById("XX")).isEmpty();
    }

    @Test
    void createOrUpdateRuta_conduccionCoincide_usaLaCorrecta() {
        Conduccion c1 = conduccion("C1");
        Conduccion c2 = conduccion("C2");
        when(conduccionRepo.findAll()).thenReturn(List.of(c1, c2));

        Ruta saved = new Ruta();
        when(rutaMapper.toEntity(any(), eq(c2))).thenReturn(saved);
        when(rutaRepo.save(saved)).thenReturn(saved);
        when(rutaMapper.toDTO(saved)).thenReturn(dto("R01", "C2"));

        RutaDTO result = service.createOrUpdateRuta(dto("R01", "C2"));
        assertThat(result.getCodigoConduccion()).isEqualTo("C2");
        verify(rutaMapper).toEntity(any(), eq(c2));
    }

    @Test
    void createOrUpdateRuta_conduccionNoCoincide_usaPrimera() {
        Conduccion c1 = conduccion("C1");
        when(conduccionRepo.findAll()).thenReturn(List.of(c1));

        Ruta saved = new Ruta();
        when(rutaMapper.toEntity(any(), eq(c1))).thenReturn(saved);
        when(rutaRepo.save(saved)).thenReturn(saved);
        when(rutaMapper.toDTO(saved)).thenReturn(dto("R01", "C1"));

        RutaDTO result = service.createOrUpdateRuta(dto("R01", "NO_EXISTE"));
        verify(rutaMapper).toEntity(any(), eq(c1));
    }
}
```

- [ ] **Step 2: Run tests**

```bash
mvn test -pl dipalza -Dfrontend.skip=true -Dtest=RutaServiceTest
```

Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 3: Commit**

```bash
cd /Users/cursor/Dev/dipalza/dipalza.springboot
git add dipalza/src/test/java/cl/eos/dipalza/service/RutaServiceTest.java
git commit -m "test: agrega pruebas unitarias para RutaService"
```

---

## Task 8: ConfiguracionService + VentaDetalleService + RefreshTokenService

**Files:**
- Create: `src/test/java/cl/eos/dipalza/service/ConfiguracionServiceTest.java`
- Create: `src/test/java/cl/eos/dipalza/service/VentaDetalleServiceTest.java`
- Create: `src/test/java/cl/eos/dipalza/service/RefreshTokenServiceTest.java`

**Interfaces:**
- `ConfiguracionService(ConfiguracionRepository)` — constructor injection; `@PostConstruct cargarCache()` won't fire without Spring context → call manually in `@BeforeEach`.
- `VentaDetalleService` — field-injected `VentaDetalleRepository`.
- `RefreshTokenService` — field-injected `RefreshTokenRepo`.
- Produces: 13 green tests.

- [ ] **Step 1: Write failing tests — ConfiguracionServiceTest**

Create `src/test/java/cl/eos/dipalza/service/ConfiguracionServiceTest.java`:

```java
package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.Configuracion;
import cl.eos.dipalza.repository.ConfiguracionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfiguracionServiceTest {

    @Mock ConfiguracionRepository repo;
    ConfiguracionService service;

    private Configuracion config(String clave, String valor) {
        Configuracion c = new Configuracion();
        c.setPropiedad(clave);
        c.setValor(valor);
        return c;
    }

    @BeforeEach
    void setUp() {
        service = new ConfiguracionService(repo);
        when(repo.findAll()).thenReturn(List.of(
                config("clave.texto", "hola"),
                config("clave.entero", "42"),
                config("clave.decimal", "3.14"),
                config("clave.bool", "true")
        ));
        service.cargarCache();
    }

    @Test
    void getString_claveExistente_retornaValor() {
        assertThat(service.getString("clave.texto")).isEqualTo("hola");
    }

    @Test
    void getString_claveInexistente_retornaVacio() {
        assertThat(service.getString("no.existe")).isEqualTo("");
    }

    @Test
    void getInt_claveExistente_retornaEntero() {
        assertThat(service.getInt("clave.entero")).isEqualTo(42);
    }

    @Test
    void getInt_valorNoNumerico_retornaCero() {
        when(repo.findAll()).thenReturn(List.of(config("clave.rota", "abc")));
        service.cargarCache();
        assertThat(service.getInt("clave.rota")).isEqualTo(0);
    }

    @Test
    void getDouble_claveExistente_retornaDecimal() {
        assertThat(service.getDouble("clave.decimal")).isEqualTo(3.14);
    }

    @Test
    void getBoolean_claveTrue_retornaTrue() {
        assertThat(service.getBoolean("clave.bool")).isTrue();
    }

    @Test
    void getBoolean_claveInexistente_retornaFalse() {
        assertThat(service.getBoolean("no.existe")).isFalse();
    }

    @Test
    void actualizarConfig_claveExistente_actualizaCacheYRepo() {
        Configuracion existing = config("clave.texto", "hola");
        when(repo.findById("clave.texto")).thenReturn(java.util.Optional.of(existing));
        when(repo.save(existing)).thenReturn(existing);

        service.actualizarConfig("clave.texto", "mundo");
        assertThat(service.getString("clave.texto")).isEqualTo("mundo");
        verify(repo).save(existing);
    }

    @Test
    void actualizarConfig_claveInexistente_lanzaRuntimeException() {
        when(repo.findById("no.existe")).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service.actualizarConfig("no.existe", "x"))
                .isInstanceOf(RuntimeException.class);
    }
}
```

- [ ] **Step 2: Write failing tests — VentaDetalleServiceTest**

Create `src/test/java/cl/eos/dipalza/service/VentaDetalleServiceTest.java`:

```java
package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.VentaDetalle;
import cl.eos.dipalza.repository.VentaDetalleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VentaDetalleServiceTest {

    @Mock VentaDetalleRepository repo;
    @InjectMocks VentaDetalleService service;

    private VentaDetalle detalle(Long id) {
        VentaDetalle d = new VentaDetalle();
        d.setId(id);
        d.setCantidad(BigDecimal.ONE);
        d.setPrecioUnitario(BigDecimal.valueOf(1000));
        d.setPorcentajeDescuento(BigDecimal.ZERO);
        d.setPorcentajeIva(BigDecimal.valueOf(19));
        d.setPorcentajeIla(BigDecimal.ZERO);
        return d;
    }

    @Test
    void listarDetallesOptimized_conResultados_retornaDTOs() {
        when(repo.findAllOptimizedByVentaId(10L)).thenReturn(List.of(detalle(1L), detalle(2L)));
        assertThat(service.listarDetallesOptimized(10L)).hasSize(2);
    }

    @Test
    void listarDetallesOptimized_sinResultados_retornaListaVacia() {
        when(repo.findAllOptimizedByVentaId(99L)).thenReturn(List.of());
        assertThat(service.listarDetallesOptimized(99L)).isEmpty();
    }
}
```

- [ ] **Step 3: Write failing tests — RefreshTokenServiceTest**

Create `src/test/java/cl/eos/dipalza/service/RefreshTokenServiceTest.java`:

```java
package cl.eos.dipalza.service;

import cl.eos.dipalza.repository.RefreshTokenRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock RefreshTokenRepo repo;
    @InjectMocks RefreshTokenService service;

    @Test
    void purgeExpiredTokens_llamaAlRepositoryConInstantePasado() {
        Instant antes = Instant.now();
        service.purgeExpiredTokens();
        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(repo).deleteByExpiresAtBefore(captor.capture());
        assertThat(captor.getValue()).isAfterOrEqualTo(antes);
    }

    @Test
    void purgeExpiredTokens_noLanzaExcepcion() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.purgeExpiredTokens());
    }
}
```

- [ ] **Step 4: Run tests**

```bash
mvn test -pl dipalza -Dfrontend.skip=true \
  -Dtest="ConfiguracionServiceTest,VentaDetalleServiceTest,RefreshTokenServiceTest"
```

Expected: `Tests run: 13, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
cd /Users/cursor/Dev/dipalza/dipalza.springboot
git add dipalza/src/test/java/cl/eos/dipalza/service/ConfiguracionServiceTest.java \
        dipalza/src/test/java/cl/eos/dipalza/service/VentaDetalleServiceTest.java \
        dipalza/src/test/java/cl/eos/dipalza/service/RefreshTokenServiceTest.java
git commit -m "test: agrega pruebas unitarias para Configuracion, VentaDetalle y RefreshToken services"
```

---

## Task 9: PosicionService unit tests

**Files:**
- Create: `src/test/java/cl/eos/dipalza/service/PosicionServiceTest.java`

**Interfaces:**
- Consumes: `PosicionService(PosicionRepository, HistorialPosicionRepository, VendedorRepository, SimpMessagingTemplate)` — constructor injection.
- Produces: 4 green tests covering `obtenerActuales`, `registrarUbicacion` con/sin posición previa, y broadcast WebSocket.

- [ ] **Step 1: Write failing tests**

Create `src/test/java/cl/eos/dipalza/service/PosicionServiceTest.java`:

```java
package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.Posicion;
import cl.eos.dipalza.entity.Vendedor;
import cl.eos.dipalza.entity.ids.VendedorId;
import cl.eos.dipalza.model.PosicionDTO;
import cl.eos.dipalza.repository.HistorialPosicionRepository;
import cl.eos.dipalza.repository.PosicionRepository;
import cl.eos.dipalza.repository.VendedorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PosicionServiceTest {

    @Mock PosicionRepository posicionRepo;
    @Mock HistorialPosicionRepository historialRepo;
    @Mock VendedorRepository vendedorRepo;
    @Mock SimpMessagingTemplate messagingTemplate;
    @InjectMocks PosicionService service;

    private PosicionDTO dto(String vendedorId) {
        return new PosicionDTO(vendedorId, "0 ", "Juan", LocalDateTime.now(), -33.4, -70.6);
    }

    @Test
    void obtenerActuales_retornaListaMapeada() {
        Posicion p = new Posicion();
        p.setId(new VendedorId("V01", "0 "));
        p.setLatitud(-33.4);
        p.setLongitud(-70.6);
        p.setFechaHora(LocalDateTime.now());
        Vendedor v = new Vendedor();
        v.setId(new VendedorId("V01", "0 "));
        v.setNombre("Juan");
        p.setVendedor(v);

        when(posicionRepo.findAll()).thenReturn(List.of(p));
        List<PosicionDTO> result = service.obtenerActuales();
        assertThat(result).hasSize(1);
    }

    @Test
    void registrarUbicacion_posicionNueva_creaNuevaEntidad() {
        VendedorId vid = new VendedorId("V01", "0 ");
        Vendedor vend = new Vendedor();
        vend.setId(vid);

        when(vendedorRepo.getReferenceById(vid)).thenReturn(vend);
        when(posicionRepo.findByVendedorId(vid)).thenReturn(null);

        service.registrarUbicacion(dto("V01"));

        verify(posicionRepo).save(any());
        verify(historialRepo).save(any());
    }

    @Test
    void registrarUbicacion_posicionExistente_actualizaEntidad() {
        VendedorId vid = new VendedorId("V01", "0 ");
        Vendedor vend = new Vendedor();
        vend.setId(vid);

        Posicion existente = new Posicion();
        existente.setId(vid);

        when(vendedorRepo.getReferenceById(vid)).thenReturn(vend);
        when(posicionRepo.findByVendedorId(vid)).thenReturn(existente);

        service.registrarUbicacion(dto("V01"));

        verify(posicionRepo).save(existente);
    }

    @Test
    void registrarUbicacion_enviaAlTopicWebSocket() {
        VendedorId vid = new VendedorId("V01", "0 ");
        Vendedor vend = new Vendedor();
        vend.setId(vid);
        when(vendedorRepo.getReferenceById(vid)).thenReturn(vend);
        when(posicionRepo.findByVendedorId(vid)).thenReturn(null);

        PosicionDTO dto = dto("V01");
        service.registrarUbicacion(dto);

        verify(messagingTemplate).convertAndSend("/topic/posiciones", dto);
    }
}
```

- [ ] **Step 2: Run tests**

```bash
mvn test -pl dipalza -Dfrontend.skip=true -Dtest=PosicionServiceTest
```

Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 3: Commit**

```bash
cd /Users/cursor/Dev/dipalza/dipalza.springboot
git add dipalza/src/test/java/cl/eos/dipalza/service/PosicionServiceTest.java
git commit -m "test: agrega pruebas unitarias para PosicionService"
```

---

## Task 10: Controllers simples (Ping, Ruta, CondicionVenta, Conduccion, Ila)

**Files:**
- Create: `src/test/java/cl/eos/dipalza/controller/PingControllerTest.java`
- Create: `src/test/java/cl/eos/dipalza/controller/RutaControllerTest.java`
- Create: `src/test/java/cl/eos/dipalza/controller/CondicionVentaControllerTest.java`
- Create: `src/test/java/cl/eos/dipalza/controller/ConduccionControllerTest.java`
- Create: `src/test/java/cl/eos/dipalza/controller/IlaControllerTest.java`

**Interfaces:**
- Produces: 14 green tests. All use `@WebMvcTest` + `excludeAutoConfiguration = SecurityAutoConfiguration.class`.

- [ ] **Step 1: Write PingControllerTest**

Create `src/test/java/cl/eos/dipalza/controller/PingControllerTest.java`:

```java
package cl.eos.dipalza.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = PingController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
class PingControllerTest {

    @Autowired MockMvc mockMvc;

    @Test
    void health_retorna200() throws Exception {
        mockMvc.perform(get("/ping"))
                .andExpect(status().isOk());
    }

    @Test
    void health_retornaStatusUP() throws Exception {
        mockMvc.perform(get("/ping"))
                .andExpect(jsonPath("$.status", is("UP")))
                .andExpect(jsonPath("$.service", is("VentasAPI")));
    }
}
```

- [ ] **Step 2: Write RutaControllerTest**

Create `src/test/java/cl/eos/dipalza/controller/RutaControllerTest.java`:

```java
package cl.eos.dipalza.controller;

import cl.eos.dipalza.model.RutaDTO;
import cl.eos.dipalza.service.RutaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = RutaController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
class RutaControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean RutaService service;

    private RutaDTO dto(String codigo) {
        RutaDTO d = new RutaDTO();
        d.setCodigo(codigo);
        d.setDescripcion("Ruta Norte");
        d.setCodigoConduccion("C1");
        return d;
    }

    @Test
    void getAllRutas_retornaLista() throws Exception {
        when(service.getAllRutas()).thenReturn(List.of(dto("R01")));
        mockMvc.perform(get("/api/rutas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].codigo", is("R01")));
    }

    @Test
    void getRutaById_existente_retorna200() throws Exception {
        when(service.getRutaById("R01")).thenReturn(Optional.of(dto("R01")));
        mockMvc.perform(get("/api/rutas/R01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codigo", is("R01")));
    }

    @Test
    void getRutaById_noExiste_retorna404() throws Exception {
        when(service.getRutaById("XX")).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/rutas/XX"))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 3: Write CondicionVentaControllerTest**

Create `src/test/java/cl/eos/dipalza/controller/CondicionVentaControllerTest.java`:

```java
package cl.eos.dipalza.controller;

import cl.eos.dipalza.model.CondicionVentaDTO;
import cl.eos.dipalza.service.CondicionVentaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = CondicionVentaController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
class CondicionVentaControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean CondicionVentaService service;

    private CondicionVentaDTO dto(String codigo) {
        CondicionVentaDTO d = new CondicionVentaDTO();
        d.setCodigo(codigo);
        d.setDescripcion("Contado");
        d.setDias(0);
        return d;
    }

    @Test
    void getAllConduccion_retornaLista() throws Exception {
        when(service.getAllCondicionVentas()).thenReturn(List.of(dto("01")));
        mockMvc.perform(get("/api/condicionventa"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].codigo", is("01")));
    }

    @Test
    void getRutaById_existente_retorna200() throws Exception {
        when(service.getCondicionVentaById("01")).thenReturn(Optional.of(dto("01")));
        mockMvc.perform(get("/api/condicionventa/01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.descripcion", is("Contado")));
    }

    @Test
    void getRutaById_noExiste_retorna404() throws Exception {
        when(service.getCondicionVentaById("99")).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/condicionventa/99"))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 4: Write ConduccionControllerTest**

Create `src/test/java/cl/eos/dipalza/controller/ConduccionControllerTest.java`:

```java
package cl.eos.dipalza.controller;

import cl.eos.dipalza.model.ConduccionDTO;
import cl.eos.dipalza.service.ConduccionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = ConduccionController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
class ConduccionControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean ConduccionService service;

    private ConduccionDTO dto(String codigo) {
        ConduccionDTO d = new ConduccionDTO();
        d.setCodigo(codigo);
        d.setDescripcion("Camión");
        d.setValor(BigDecimal.valueOf(1500));
        return d;
    }

    @Test
    void getAllConduccion_retornaLista() throws Exception {
        when(service.getAllConduccions()).thenReturn(List.of(dto("C1")));
        mockMvc.perform(get("/api/conduccion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].codigo", is("C1")));
    }

    @Test
    void getConduccionById_existente_retorna200() throws Exception {
        when(service.getConduccionById("C1")).thenReturn(Optional.of(dto("C1")));
        mockMvc.perform(get("/api/conduccion/C1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.descripcion", is("Camión")));
    }

    @Test
    void getConduccionById_noExiste_retorna404() throws Exception {
        when(service.getConduccionById("XX")).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/conduccion/XX"))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 5: Write IlaControllerTest**

Create `src/test/java/cl/eos/dipalza/controller/IlaControllerTest.java`:

```java
package cl.eos.dipalza.controller;

import cl.eos.dipalza.model.IlaDTO;
import cl.eos.dipalza.service.IlaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = IlaController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
class IlaControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean IlaService service;

    private IlaDTO dto(String codigo) {
        IlaDTO d = new IlaDTO();
        d.setCodigo(codigo);
        d.setDescripcion("Bebidas alcohólicas");
        d.setValor(BigDecimal.valueOf(27));
        return d;
    }

    @Test
    void getAllIla_retornaLista() throws Exception {
        when(service.findAllByOrderByDescripcionAsc()).thenReturn(List.of(dto("I1")));
        mockMvc.perform(get("/api/ila"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].codigo", is("I1")));
    }

    @Test
    void getIlaById_existente_retorna200() throws Exception {
        when(service.getIlaById("I1")).thenReturn(Optional.of(dto("I1")));
        mockMvc.perform(get("/api/ila/I1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.descripcion", is("Bebidas alcohólicas")));
    }

    @Test
    void getIlaById_noExiste_retorna404() throws Exception {
        when(service.getIlaById("XX")).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/ila/XX"))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 6: Run tests**

```bash
mvn test -pl dipalza -Dfrontend.skip=true \
  -Dtest="PingControllerTest,RutaControllerTest,CondicionVentaControllerTest,ConduccionControllerTest,IlaControllerTest"
```

Expected: `Tests run: 14, Failures: 0, Errors: 0`

- [ ] **Step 7: Commit**

```bash
cd /Users/cursor/Dev/dipalza/dipalza.springboot
git add \
  dipalza/src/test/java/cl/eos/dipalza/controller/PingControllerTest.java \
  dipalza/src/test/java/cl/eos/dipalza/controller/RutaControllerTest.java \
  dipalza/src/test/java/cl/eos/dipalza/controller/CondicionVentaControllerTest.java \
  dipalza/src/test/java/cl/eos/dipalza/controller/ConduccionControllerTest.java \
  dipalza/src/test/java/cl/eos/dipalza/controller/IlaControllerTest.java
git commit -m "test: agrega pruebas para controllers Ping, Ruta, CondicionVenta, Conduccion e Ila"
```

---

## Task 11: PosicionController + VentaDetalleController + FacturacionController

**Files:**
- Create: `src/test/java/cl/eos/dipalza/controller/PosicionControllerTest.java`
- Create: `src/test/java/cl/eos/dipalza/controller/VentaDetalleControllerTest.java`
- Create: `src/test/java/cl/eos/dipalza/controller/FacturacionControllerTest.java`

**Interfaces:**
- Produces: 7 green tests.

- [ ] **Step 1: Write PosicionControllerTest**

Create `src/test/java/cl/eos/dipalza/controller/PosicionControllerTest.java`:

```java
package cl.eos.dipalza.controller;

import cl.eos.dipalza.model.HistorialPosicionDTO;
import cl.eos.dipalza.model.PosicionDTO;
import cl.eos.dipalza.service.PosicionService;
import cl.eos.dipalza.specifications.PosicionFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = PosicionController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
class PosicionControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean PosicionService service;
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void obtenerPosiciones_retorna200ConLista() throws Exception {
        PosicionDTO dto = new PosicionDTO("V01", "0 ", "Juan", LocalDateTime.now(), -33.4, -70.6);
        when(service.obtenerActuales()).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/posicion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].vendedorId", is("V01")));
    }

    @Test
    void obtenerHistorico_conFiltro_retorna200() throws Exception {
        HistorialPosicionDTO h = new HistorialPosicionDTO("V01", "0 ", "Juan", LocalDateTime.now(), -33.4, -70.6);
        when(service.buscarHistorico(any())).thenReturn(List.of(h));

        PosicionFilter filter = new PosicionFilter();
        mockMvc.perform(post("/api/posicion/historico")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(filter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void registrarPosicion_retorna202() throws Exception {
        PosicionDTO dto = new PosicionDTO("V01", "0 ", "Juan", LocalDateTime.now(), -33.4, -70.6);
        doNothing().when(service).registrarUbicacion(any());

        mockMvc.perform(post("/api/posicion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isAccepted());
    }
}
```

**Note:** `HistorialPosicionDTO` and `PosicionFilter` must have accessible constructors. If `HistorialPosicionDTO` is a record, adjust accordingly — check [HistorialPosicionDTO.java](src/main/java/cl/eos/dipalza/model/HistorialPosicionDTO.java) before coding. If `PosicionFilter` lacks a no-arg constructor, use `new PosicionFilter(null, null, null)` or the actual constructor signature.

- [ ] **Step 2: Check HistorialPosicionDTO and PosicionFilter constructors**

```bash
grep -n "class\|record\|public " \
  /Users/cursor/Dev/dipalza/dipalza.springboot/dipalza/src/main/java/cl/eos/dipalza/model/HistorialPosicionDTO.java \
  /Users/cursor/Dev/dipalza/dipalza.springboot/dipalza/src/main/java/cl/eos/dipalza/specifications/PosicionFilter.java
```

Adjust the `PosicionControllerTest` constructor calls if needed before continuing.

- [ ] **Step 3: Write VentaDetalleControllerTest**

Create `src/test/java/cl/eos/dipalza/controller/VentaDetalleControllerTest.java`:

```java
package cl.eos.dipalza.controller;

import cl.eos.dipalza.model.venta.VentaDetalleDTO;
import cl.eos.dipalza.service.VentaDetalleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = VentaDetalleController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
class VentaDetalleControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean VentaDetalleService service;

    @Test
    void listarDetalles_conResultados_retorna200() throws Exception {
        VentaDetalleDTO dto = new VentaDetalleDTO();
        dto.setId(1L);
        when(service.listarDetallesOptimized(10L)).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/ventadetalle/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void listarDetalles_sinResultados_retornaArrayVacio() throws Exception {
        when(service.listarDetallesOptimized(99L)).thenReturn(List.of());

        mockMvc.perform(get("/api/ventadetalle/99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }
}
```

- [ ] **Step 4: Write FacturacionControllerTest**

Create `src/test/java/cl/eos/dipalza/controller/FacturacionControllerTest.java`:

```java
package cl.eos.dipalza.controller;

import cl.eos.dipalza.service.FacturacionService;
import cl.eos.dipalza.service.resultados.VentaFacturaResultado;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = FacturacionController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
class FacturacionControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean FacturacionService service;

    @Test
    void facturarVentas_conResultados_retorna200() throws Exception {
        VentaFacturaResultado resultado = new VentaFacturaResultado(
                "FAC001", LocalDateTime.now(), BigDecimal.valueOf(1190), List.of(), "OK");
        when(service.facturar()).thenReturn(List.of(resultado));

        mockMvc.perform(post("/api/facturacion").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void facturarVentas_sinResultados_retorna204() throws Exception {
        when(service.facturar()).thenReturn(List.of());

        mockMvc.perform(post("/api/facturacion").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNoContent());
    }
}
```

- [ ] **Step 5: Run tests**

```bash
mvn test -pl dipalza -Dfrontend.skip=true \
  -Dtest="PosicionControllerTest,VentaDetalleControllerTest,FacturacionControllerTest"
```

Expected: `Tests run: 7, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
cd /Users/cursor/Dev/dipalza/dipalza.springboot
git add \
  dipalza/src/test/java/cl/eos/dipalza/controller/PosicionControllerTest.java \
  dipalza/src/test/java/cl/eos/dipalza/controller/VentaDetalleControllerTest.java \
  dipalza/src/test/java/cl/eos/dipalza/controller/FacturacionControllerTest.java
git commit -m "test: agrega pruebas para controllers Posicion, VentaDetalle y Facturacion"
```

---

## Task 12: NumeradosController + ClienteController

**Files:**
- Create: `src/test/java/cl/eos/dipalza/controller/NumeradosControllerTest.java`
- Create: `src/test/java/cl/eos/dipalza/controller/ClienteControllerTest.java`

**Interfaces:**
- Produces: 14 green tests.

- [ ] **Step 1: Write NumeradosControllerTest**

Create `src/test/java/cl/eos/dipalza/controller/NumeradosControllerTest.java`:

```java
package cl.eos.dipalza.controller;

import cl.eos.dipalza.model.NumeradoDTO;
import cl.eos.dipalza.model.NumeradoResumenDTO;
import cl.eos.dipalza.service.NumeradosService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = NumeradosController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
class NumeradosControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean NumeradosService service;

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
    void getAllNumerados_retornaLista() throws Exception {
        when(service.findAll()).thenReturn(List.of(dto(1L)));
        mockMvc.perform(get("/api/numerados"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].codigoProducto", is("ART001")));
    }

    @Test
    void getNumeradosByCodigoProducto_retornaFiltrados() throws Exception {
        when(service.findByProducto("ART001")).thenReturn(List.of(dto(1L)));
        mockMvc.perform(get("/api/numerados/byProduct").param("codigoProducto", "ART001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void getGroupedNumerados_retornaResumen() throws Exception {
        NumeradoResumenDTO resumen = new NumeradoResumenDTO("ART001", "Queso", 5L, BigDecimal.valueOf(12.5));
        when(service.findGrouped()).thenReturn(List.of(resumen));
        mockMvc.perform(get("/api/numerados/resumen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void getNumeradosByEstado_retornaFiltrados() throws Exception {
        when(service.findAllByEstado("D")).thenReturn(List.of(dto(1L)));
        mockMvc.perform(get("/api/numerados/estados").param("estado", "D"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void createNumerado_retornaDTO() throws Exception {
        NumeradoDTO d = dto(null);
        when(service.save(any())).thenReturn(dto(1L));
        mockMvc.perform(post("/api/numerados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(d)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(1)));
    }

    @Test
    void findPesoPromedioArticulo_retornaFloat() throws Exception {
        when(service.findPrecioPromedioArticulo("ART001")).thenReturn(15.5f);
        mockMvc.perform(get("/api/numerados/pesopromedio/ART001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", is(15.5)));
    }
}
```

**Note:** `NumeradoResumenDTO` constructor — check its definition in [NumeradoResumenDTO.java](src/main/java/cl/eos/dipalza/model/NumeradoResumenDTO.java) and adjust the constructor call if needed.

- [ ] **Step 2: Check NumeradoResumenDTO constructor**

```bash
cat /Users/cursor/Dev/dipalza/dipalza.springboot/dipalza/src/main/java/cl/eos/dipalza/model/NumeradoResumenDTO.java
```

If it's a record `record NumeradoResumenDTO(String articulo, String descripcion, Long cantidad, BigDecimal promedio)`, the code above is correct. If it's a class, use setters instead.

- [ ] **Step 3: Write ClienteControllerTest**

Create `src/test/java/cl/eos/dipalza/controller/ClienteControllerTest.java`:

```java
package cl.eos.dipalza.controller;

import cl.eos.dipalza.model.ClienteDTO;
import cl.eos.dipalza.service.ClienteService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = ClienteController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
class ClienteControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean ClienteService service;

    private ClienteDTO dto(String rut, String codigo) {
        ClienteDTO d = new ClienteDTO();
        d.setRut(rut);
        d.setCodigo(codigo);
        d.setRazon("Empresa Test SA");
        d.setCodigoRuta("R01");
        return d;
    }

    @Test
    void getAllClientes_retornaLista() throws Exception {
        when(service.getAllClientes()).thenReturn(List.of(dto("11111111-1", "001")));
        mockMvc.perform(get("/api/clientes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rut", is("11111111-1")));
    }

    @Test
    void getClientesByRuta_retornaFiltrados() throws Exception {
        when(service.getClientesByRuta("R01")).thenReturn(List.of(dto("11111111-1", "001")));
        mockMvc.perform(get("/api/clientes/ruta/R01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void getClienteById_existente_retorna200() throws Exception {
        when(service.getClienteById("11111111-1", "001")).thenReturn(Optional.of(dto("11111111-1", "001")));
        mockMvc.perform(get("/api/clientes/11111111-1").param("codigo", "001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.razon", is("Empresa Test SA")));
    }

    @Test
    void getClienteById_noExiste_retorna404() throws Exception {
        when(service.getClienteById("99999999-9", "   ")).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/clientes/99999999-9"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getClienteByVendedor_retornaLista() throws Exception {
        when(service.getClientesByVendedor("V01")).thenReturn(List.of(dto("11111111-1", "001")));
        mockMvc.perform(get("/api/clientes/vendedor").param("codigoVendedor", "V01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void createCliente_retornaDTO() throws Exception {
        ClienteDTO d = dto("11111111-1", "001");
        when(service.createOrUpdateCliente(any())).thenReturn(d);
        mockMvc.perform(post("/api/clientes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(d)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rut", is("11111111-1")));
    }

    @Test
    void updateCliente_existente_retorna200() throws Exception {
        ClienteDTO d = dto("11111111-1", "001");
        when(service.getClienteById("11111111-1", "001")).thenReturn(Optional.of(d));
        when(service.createOrUpdateCliente(any())).thenReturn(d);
        mockMvc.perform(put("/api/clientes/11111111-1/001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(d)))
                .andExpect(status().isOk());
    }

    @Test
    void deleteCliente_existente_retorna204() throws Exception {
        when(service.deleteCliente("11111111-1", "001")).thenReturn(true);
        mockMvc.perform(delete("/api/clientes/11111111-1/001"))
                .andExpect(status().isNoContent());
    }
}
```

- [ ] **Step 4: Run tests**

```bash
mvn test -pl dipalza -Dfrontend.skip=true \
  -Dtest="NumeradosControllerTest,ClienteControllerTest"
```

Expected: `Tests run: 14, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
cd /Users/cursor/Dev/dipalza/dipalza.springboot
git add \
  dipalza/src/test/java/cl/eos/dipalza/controller/NumeradosControllerTest.java \
  dipalza/src/test/java/cl/eos/dipalza/controller/ClienteControllerTest.java
git commit -m "test: agrega pruebas para controllers Numerados y Cliente"
```

---

## Task 13: EstadoVenta bug fix + VentaController tests

**Files:**
- Modify: `src/main/java/cl/eos/dipalza/entity/EstadoVenta.java`
- Create: `src/test/java/cl/eos/dipalza/entity/EstadoVentaTest.java`
- Create: `src/test/java/cl/eos/dipalza/controller/VentaControllerTest.java`

**Interfaces:**
- Bug fix: `EstadoVenta.fromName()` currently returns `OPENED` in the catch block instead of `null`. This prevents VentaController from returning 400 on invalid state names.
- Produces: 13 green tests (3 entity + 10 controller).

- [ ] **Step 1: Write failing entity test to confirm the bug**

Create `src/test/java/cl/eos/dipalza/entity/EstadoVentaTest.java`:

```java
package cl.eos.dipalza.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EstadoVentaTest {

    @Test
    void fromName_estadoValido_retornaEnum() {
        assertThat(EstadoVenta.fromName("FINISHED")).isEqualTo(EstadoVenta.FINISHED);
    }

    @Test
    void fromName_estadoEnBlanco_retornaOpened() {
        assertThat(EstadoVenta.fromName("")).isEqualTo(EstadoVenta.OPENED);
        assertThat(EstadoVenta.fromName(null)).isEqualTo(EstadoVenta.OPENED);
    }

    @Test
    void fromName_estadoInvalido_retornaNull() {
        assertThat(EstadoVenta.fromName("ESTADO_INVALIDO")).isNull();
    }
}
```

- [ ] **Step 2: Run entity test — confirmará que `fromName_estadoInvalido_retornaNull` FALLA**

```bash
mvn test -pl dipalza -Dfrontend.skip=true -Dtest=EstadoVentaTest
```

Expected: `Tests run: 3, Failures: 1` — el test de estado inválido falla porque devuelve `OPENED` en vez de `null`.

- [ ] **Step 3: Arreglar el bug en EstadoVenta.java**

Editar `src/main/java/cl/eos/dipalza/entity/EstadoVenta.java`, cambiar el bloque `catch`:

**Antes:**
```java
        } catch (IllegalArgumentException e) {
            return OPENED;
        }
```

**Después:**
```java
        } catch (IllegalArgumentException e) {
            return null;
        }
```

- [ ] **Step 4: Re-ejecutar test de entidad — ahora debe pasar**

```bash
mvn test -pl dipalza -Dfrontend.skip=true -Dtest=EstadoVentaTest
```

Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 5: Write VentaControllerTest**

Create `src/test/java/cl/eos/dipalza/controller/VentaControllerTest.java`:

```java
package cl.eos.dipalza.controller;

import cl.eos.dipalza.entity.EstadoVenta;
import cl.eos.dipalza.entity.Venta;
import cl.eos.dipalza.model.EstadoVentaDTO;
import cl.eos.dipalza.model.venta.VentaDTO;
import cl.eos.dipalza.repository.NumeradoRepository;
import cl.eos.dipalza.service.VentaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = VentaController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
class VentaControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean VentaService ventaService;
    @MockBean NumeradoRepository numeradoRepository;
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private VentaDTO ventaDto(Long id) {
        VentaDTO d = new VentaDTO();
        d.setId(id);
        d.setFecha(LocalDate.now());
        d.setEstadoVenta(EstadoVenta.OPENED.name());
        d.setTotal(BigDecimal.valueOf(1190));
        return d;
    }

    private Venta ventaEntity(Long id) {
        Venta v = new Venta();
        v.setId(id);
        v.setFecha(LocalDate.now());
        v.setEstado(EstadoVenta.OPENED);
        v.setTotal(BigDecimal.valueOf(1190));
        v.setTotalNeto(BigDecimal.valueOf(1000));
        v.setTotalIva(BigDecimal.valueOf(190));
        v.setTotalIla(BigDecimal.ZERO);
        v.setTotalDescuento(BigDecimal.ZERO);
        return v;
    }

    @Test
    void listarVentas_retorna200ConLista() throws Exception {
        when(ventaService.listarVentas(any())).thenReturn(List.of(ventaEntity(1L)));
        mockMvc.perform(get("/api/ventas"))
                .andExpect(status().isOk());
    }

    @Test
    void listarVentasPendingByVendedor_retorna200() throws Exception {
        when(ventaService.listarVentas(any())).thenReturn(List.of(ventaEntity(1L)));
        mockMvc.perform(get("/api/ventas/pending/V01"))
                .andExpect(status().isOk());
    }

    @Test
    void grabarVenta_sinId_llamaCrearVenta() throws Exception {
        VentaDTO dto = ventaDto(-1L);
        when(ventaService.crearVenta(any())).thenReturn(ventaDto(1L));
        mockMvc.perform(post("/api/ventas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk());
        verify(ventaService).crearVenta(any());
    }

    @Test
    void grabarVenta_conId_llamaActualizarVenta() throws Exception {
        VentaDTO dto = ventaDto(5L);
        when(ventaService.actualizarVenta(eq(5L), any())).thenReturn(ventaDto(5L));
        mockMvc.perform(post("/api/ventas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk());
        verify(ventaService).actualizarVenta(eq(5L), any());
    }

    @Test
    void eliminarVenta_eliminado_retorna204() throws Exception {
        when(ventaService.eliminarVenta(1L)).thenReturn(true);
        mockMvc.perform(delete("/api/ventas/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void eliminarVenta_noExiste_retorna404() throws Exception {
        when(ventaService.eliminarVenta(99L)).thenReturn(false);
        mockMvc.perform(delete("/api/ventas/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void eliminarVenta_illegalState_retorna409() throws Exception {
        when(ventaService.eliminarVenta(1L)).thenThrow(new IllegalStateException("no se puede"));
        mockMvc.perform(delete("/api/ventas/1"))
                .andExpect(status().isConflict());
    }

    @Test
    void updateEstadoVenta_estadoValido_retorna200() throws Exception {
        EstadoVentaDTO body = new EstadoVentaDTO(1L, "FINISHED");
        when(ventaService.actualizaEstadoVenta(1L, EstadoVenta.FINISHED)).thenReturn(ventaEntity(1L));
        mockMvc.perform(post("/api/ventas/updateEstadoVenta")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    @Test
    void updateEstadoVenta_estadoInvalido_retorna400() throws Exception {
        EstadoVentaDTO body = new EstadoVentaDTO(1L, "ESTADO_INVALIDO");
        mockMvc.perform(post("/api/ventas/updateEstadoVenta")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateEstadoVenta_idVentaNull_lanza500() throws Exception {
        // El controller llama Objects.requireNonNull antes del servicio — lanza 500 en MockMvc sin handler
        EstadoVentaDTO body = new EstadoVentaDTO(null, "FINISHED");
        mockMvc.perform(post("/api/ventas/updateEstadoVenta")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is5xxServerError());
    }
}
```

- [ ] **Step 6: Run all Task 13 tests**

```bash
mvn test -pl dipalza -Dfrontend.skip=true -Dtest="EstadoVentaTest,VentaControllerTest"
```

Expected: `Tests run: 13, Failures: 0, Errors: 0`

- [ ] **Step 7: Commit**

```bash
cd /Users/cursor/Dev/dipalza/dipalza.springboot
git add \
  dipalza/src/main/java/cl/eos/dipalza/entity/EstadoVenta.java \
  dipalza/src/test/java/cl/eos/dipalza/entity/EstadoVentaTest.java \
  dipalza/src/test/java/cl/eos/dipalza/controller/VentaControllerTest.java
git commit -m "fix: EstadoVenta.fromName retorna null para estados inválidos; test: agrega EstadoVentaTest y VentaControllerTest"
```

---

## Task 14: AuthController tests

**Files:**
- Create: `src/test/java/cl/eos/dipalza/controller/AuthControllerTest.java`

**Interfaces:**
- Uses `@ActiveProfiles("dev-sec")` — esto carga `SecurityConfigDevSec` que crea `PasswordEncoder`, `JwtAuthFilter` y `SecurityFilterChain`.
- `JwtAuthFilter` tiene `@Autowired JwtService` y `@Autowired UserRepo` — ambos deben ser `@MockBean`.
- `AuthController` necesita: `UserRepo`, `PasswordEncoder`, `JwtService`, `RefreshTokenRepo`, `VendedorRepository` — todos `@MockBean`.
- `PasswordEncoder` viene de `SecurityConfigDevSec` (como `@Bean`), NO necesita ser `@MockBean` — Spring lo crea automáticamente desde el config.
- Produce: 7 green tests.

- [ ] **Step 1: Write failing tests**

Create `src/test/java/cl/eos/dipalza/controller/AuthControllerTest.java`:

```java
package cl.eos.dipalza.controller;

import cl.eos.dipalza.entity.AppUser;
import cl.eos.dipalza.entity.RefreshToken;
import cl.eos.dipalza.entity.Vendedor;
import cl.eos.dipalza.entity.ids.VendedorId;
import cl.eos.dipalza.repository.RefreshTokenRepo;
import cl.eos.dipalza.repository.UserRepo;
import cl.eos.dipalza.repository.VendedorRepository;
import cl.eos.dipalza.service.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("dev-sec")
@WebMvcTest(value = AuthController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
class AuthControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean UserRepo userRepo;
    @MockBean JwtService jwtService;
    @MockBean RefreshTokenRepo refreshTokenRepo;
    @MockBean VendedorRepository vendedorRepo;

    // PasswordEncoder es creado por SecurityConfigDevSec (BCrypt real), no necesita mock.
    // JwtAuthFilter lo satisface con el @MockBean JwtService + @MockBean UserRepo.

    private AppUser usuario(String username, boolean enabled, boolean locked) {
        AppUser u = new AppUser();
        u.setId(1L);
        u.setUsername(username);
        u.setPassword("$2a$10$abcdefghijklmnopqrstuuVGqG8.KqxknEZCYbSJFMV8BGOA.8yai"); // BCrypt "password"
        u.setEnabled(enabled);
        u.setLocked(locked);
        u.setRoles(Set.of());
        Vendedor v = new Vendedor();
        v.setId(new VendedorId("V01", "0 "));
        v.setNombre("Test Vendedor");
        u.setVendedor(v);
        return u;
    }

    private String loginBody(String username, String password) throws Exception {
        return objectMapper.writeValueAsString(new java.util.HashMap<String, String>() {{
            put("username", username);
            put("password", password);
        }});
    }

    @Test
    void login_usuarioNoEncontrado_retorna401() throws Exception {
        when(userRepo.findByUsername("noexiste")).thenReturn(Optional.empty());
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("noexiste", "password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_usuarioDeshabilitado_retorna401() throws Exception {
        when(userRepo.findByUsername("jdoe")).thenReturn(Optional.of(usuario("jdoe", false, false)));
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("jdoe", "password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_usuarioBloqueado_retorna401() throws Exception {
        when(userRepo.findByUsername("jdoe")).thenReturn(Optional.of(usuario("jdoe", true, true)));
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("jdoe", "password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_passwordIncorrecta_retorna401() throws Exception {
        when(userRepo.findByUsername("jdoe")).thenReturn(Optional.of(usuario("jdoe", true, false)));
        // La contraseña "wrongpassword" no coincide con el hash BCrypt de "password"
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("jdoe", "wrongpassword")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_credencialesCorrectas_retornaTokens() throws Exception {
        AppUser u = usuario("jdoe", true, false);
        when(userRepo.findByUsername("jdoe")).thenReturn(Optional.of(u));
        when(jwtService.generateAccess(any())).thenReturn("access-token");
        when(jwtService.generateRefresh(any())).thenReturn("refresh-token");
        when(refreshTokenRepo.save(any())).thenReturn(new RefreshToken());
        when(vendedorRepo.findById(any())).thenReturn(Optional.empty());

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("jdoe", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists());
    }

    @Test
    void refresh_tokenNoEncontrado_retorna401() throws Exception {
        when(refreshTokenRepo.findByTokenHash(anyString())).thenReturn(Optional.empty());
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"alguntokeninvalido\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_tokenRevocado_retorna401() throws Exception {
        RefreshToken rt = new RefreshToken();
        rt.setRevoked(true);
        rt.setExpiresAt(Instant.now().plusSeconds(3600));
        AppUser u = usuario("jdoe", true, false);
        rt.setUser(u);

        when(refreshTokenRepo.findByTokenHash(anyString())).thenReturn(Optional.of(rt));
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"alguntoken\"}"))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Run tests**

```bash
mvn test -pl dipalza -Dfrontend.skip=true -Dtest=AuthControllerTest
```

Expected: `Tests run: 7, Failures: 0, Errors: 0`

If there are Spring context failures due to SecurityConfigDevSec bean wiring, troubleshoot: ensure `@MockBean JwtService` is present (satisfies JwtAuthFilter's @Autowired), and that `PasswordEncoder` is provided by SecurityConfigDevSec (not mocked). Add `@MockBean PasswordEncoder passwordEncoder;` only if context fails saying "No qualifying bean of type PasswordEncoder".

- [ ] **Step 3: Commit**

```bash
cd /Users/cursor/Dev/dipalza/dipalza.springboot
git add dipalza/src/test/java/cl/eos/dipalza/controller/AuthControllerTest.java
git commit -m "test: agrega pruebas para AuthController con perfil dev-sec"
```

---

## Task 15: ApplicationContextIT + verificación final

**Files:**
- Modify: `src/test/java/cl/eos/dipalza/DipalzaApplicationTests.java`

**Interfaces:**
- Consumes: `application-dev-sec.yml` en test resources (H2, creado en Task 1).
- Produces: 4 green tests confirmando que el contexto Spring Boot carga completamente con H2.

- [ ] **Step 1: Expandir DipalzaApplicationTests**

Reemplazar el contenido de `src/test/java/cl/eos/dipalza/DipalzaApplicationTests.java`:

```java
package cl.eos.dipalza;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DipalzaApplicationTests {

    @Autowired ApplicationContext context;
    @Autowired MockMvc mockMvc;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    void pingEndpoint_retorna200() throws Exception {
        mockMvc.perform(get("/ping").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void repositoriosEsenciales_estanRegistrados() {
        assertThat(context.containsBean("ventaRepository")).isTrue();
        assertThat(context.containsBean("clienteRepository")).isTrue();
    }

    @Test
    void serviciosEsenciales_estanRegistrados() {
        assertThat(context.containsBean("ventaService")).isTrue();
        assertThat(context.containsBean("configuracionService")).isTrue();
    }
}
```

- [ ] **Step 2: Ejecutar ApplicationContextIT**

```bash
mvn test -pl dipalza -Dfrontend.skip=true -Dtest=DipalzaApplicationTests
```

Expected: `Tests run: 4, Failures: 0, Errors: 0`

If context fails to start, likely causes:
1. **H2 + dbo schema**: Verify `src/test/resources/application-dev-sec.yml` has `INIT=CREATE SCHEMA IF NOT EXISTS dbo` in the URL.
2. **facturacion datasource**: Verify the `facturacion.datasource.url` also uses H2 (not SQL Server).
3. **ddl-auto**: Must be `create-drop` (not `validate` or `none`) so H2 creates tables.

- [ ] **Step 3: Ejecutar toda la suite completa**

```bash
mvn test -pl dipalza -Dfrontend.skip=true
```

Expected output summary:
```
Tests run: 155+, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

(155+ = existing ~27 tests + ~128 new tests)

- [ ] **Step 4: Verificar cobertura JaCoCo**

```bash
open /Users/cursor/Dev/dipalza/dipalza.springboot/dipalza/target/site/jacoco/index.html
```

Expected: Total coverage ≥ 75% de instrucciones.

- [ ] **Step 5: Commit final**

```bash
cd /Users/cursor/Dev/dipalza/dipalza.springboot
git add dipalza/src/test/java/cl/eos/dipalza/DipalzaApplicationTests.java
git commit -m "test: expande ApplicationContextIT para verificar carga completa del contexto Spring Boot con H2"
```

---

## Resumen de archivos

| Archivo | Acción |
|---------|--------|
| `dipalza/pom.xml` | Modificar: agregar H2 scope=test |
| `src/test/resources/application-dev-sec.yml` | Crear: H2 override para tests |
| `src/main/java/.../entity/EstadoVenta.java` | Modificar: bug fix en fromName() |
| `src/test/java/.../utils/UtilsTest.java` | Crear |
| `src/test/java/.../mapper/VentaMapperTest.java` | Crear |
| `src/test/java/.../service/JwtServiceTest.java` | Crear |
| `src/test/java/.../service/CondicionVentaServiceTest.java` | Crear |
| `src/test/java/.../service/ConduccionServiceTest.java` | Crear |
| `src/test/java/.../service/IlaServiceTest.java` | Crear |
| `src/test/java/.../service/ClienteServiceTest.java` | Crear |
| `src/test/java/.../service/ProductoServiceTest.java` | Crear |
| `src/test/java/.../service/NumeradosServiceTest.java` | Crear |
| `src/test/java/.../service/RutaServiceTest.java` | Crear |
| `src/test/java/.../service/ConfiguracionServiceTest.java` | Crear |
| `src/test/java/.../service/VentaDetalleServiceTest.java` | Crear |
| `src/test/java/.../service/RefreshTokenServiceTest.java` | Crear |
| `src/test/java/.../service/PosicionServiceTest.java` | Crear |
| `src/test/java/.../controller/PingControllerTest.java` | Crear |
| `src/test/java/.../controller/RutaControllerTest.java` | Crear |
| `src/test/java/.../controller/CondicionVentaControllerTest.java` | Crear |
| `src/test/java/.../controller/ConduccionControllerTest.java` | Crear |
| `src/test/java/.../controller/IlaControllerTest.java` | Crear |
| `src/test/java/.../controller/PosicionControllerTest.java` | Crear |
| `src/test/java/.../controller/VentaDetalleControllerTest.java` | Crear |
| `src/test/java/.../controller/FacturacionControllerTest.java` | Crear |
| `src/test/java/.../controller/NumeradosControllerTest.java` | Crear |
| `src/test/java/.../controller/ClienteControllerTest.java` | Crear |
| `src/test/java/.../entity/EstadoVentaTest.java` | Crear |
| `src/test/java/.../controller/VentaControllerTest.java` | Crear |
| `src/test/java/.../controller/AuthControllerTest.java` | Crear |
| `src/test/java/.../DipalzaApplicationTests.java` | Modificar |

**Total tests nuevos objetivo:** ~128 tests distribuidos en 30 archivos (2 modificados, 28 nuevos + 1 bug fix).
