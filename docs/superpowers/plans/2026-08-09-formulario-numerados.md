# Formulario de productos numerados Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Completar el flujo de alta/edición/eliminación de productos numerados: backend (`dipalza_server`) mantiene `Producto.pieces` correcto y valida reglas de negocio; frontend (`dipalza_web_client`) tiene un formulario real (hoy es un stub) conectado a la navegación existente.

**Architecture:** `NumeradosService.save()`/`deleteById()` recalculan `Producto.pieces` (conteo de numerados en estado `D` para ese producto) tras cada escritura, y validan producto numerado + número no duplicado antes de guardar. `EdicionNumeradosComponent` (Angular, standalone) se reutiliza para alta y edición, recibiendo datos por `router` `state` (mismo patrón que ya usa `listado-numerados-de-un-producto`), y queda enlazado desde los dos listados existentes.

**Tech Stack:** Spring Boot 3 / Java, JPA (Hibernate), Mockito + JUnit 5 (backend); Angular standalone components, Reactive Forms, RxJS (frontend).

## Global Constraints

- Nunca commit directo a `main` en ningún repo. `dipalza_server`: continuar en la rama ya creada `docs/spec-formulario-numerados` (tiene el commit del spec). `dipalza_web_client`: crear rama nueva `feat/formulario-numerados` desde `main` actualizado (`git pull` primero — al inicio de esta sesión estaba 1 commit detrás de `origin/main`).
- Se puede hacer push de las ramas directo sin pedir confirmación (regla ya establecida en este proyecto), pero **no abrir Pull Request sin que el usuario lo pida explícitamente**.
- Alcance: solo `Producto.pieces`. No tocar `Producto.Stock` (pertenece al pipeline de sync con Mastersoft, ver spec).
- En este entorno de ejecución **no hay navegador headless disponible**, por lo que `ng test` (Karma) no se puede correr aquí. Cada tarea de frontend usa `npx ng build` (compila TypeScript real, sin necesitar navegador) como verificación en este entorno, y dejamos el código de test real escrito para que se corra con `ng test` en la máquina del usuario / CI.
- `mvn test` sí se puede correr en este entorno (Java/Maven, sin navegador) y se usa como verificación real en las tareas de backend.

---

## Backend (`dipalza_server`)

### Task 1: Query de unicidad de número en `NumeradoRepository`

**Files:**
- Modify: `dipalza/src/main/java/cl/eos/dipalza/repository/NumeradoRepository.java`

**Interfaces:**
- Produces: `boolean existsNumeroActivoParaProducto(String articulo, Integer numero, Long id)` — usado por `NumeradosService` en Task 2.

- [ ] **Step 1: Agregar el método al repositorio**

Reemplazar el contenido completo de `dipalza/src/main/java/cl/eos/dipalza/repository/NumeradoRepository.java` por:

```java
package cl.eos.dipalza.repository;

import cl.eos.dipalza.entity.Numerado;
import cl.eos.dipalza.model.NumeradoResumenDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NumeradoRepository extends JpaRepository<Numerado, Long> {


    @Query("SELECT n FROM Numerado n WHERE n.producto.articulo = :productoId AND n.estado = :estado order by n.id asc")
    List<Numerado> findByProductoIdAndEstadoOrderById(@Param("productoId") String productoId, @Param("estado") String estado);

    @Query("SELECT n from Numerado n WHERE n.producto.articulo = :productoId")
    List<Numerado> findByProductoId(@Param("productoId") String productoId);

    @Query("SELECT n from Numerado n WHERE n.estado = :estado")
    List<Numerado> findByEstado(@Param("estado") String productoId);

    @Query("""
    SELECT new cl.eos.dipalza.model.NumeradoResumenDTO(
        n.producto.articulo,
        n.producto.descripcion,
        SUM(n.peso),
        COUNT(n)
    )
    FROM Numerado n
    WHERE n.estado = :estado
    GROUP BY n.producto.articulo, n.producto.descripcion
""")
    List<NumeradoResumenDTO> findGroupedByEstado(@Param("estado") String estado);

    @Query("""
    SELECT COUNT(n) > 0 FROM Numerado n
    WHERE n.producto.articulo = :articulo
      AND n.numero = :numero
      AND n.estado IN ('D','R')
      AND (:id IS NULL OR n.id <> :id)
""")
    boolean existsNumeroActivoParaProducto(@Param("articulo") String articulo,
                                            @Param("numero") Integer numero,
                                            @Param("id") Long id);
}
```

- [ ] **Step 2: Verificar que el proyecto compila**

Run: `cd /Users/cursor/Dev/dipalza/application_v2.0/dipalza_server/dipalza && mvn -q -DskipTests compile`
Expected: `BUILD SUCCESS` (el método nuevo compila; se prueba indirectamente en Task 2, no hay tests de repositorio en este proyecto — el patrón existente en `NumeradoRepository`/`ProductoRepository` no tiene `@DataJpaTest`, así que no se agrega uno nuevo aquí).

- [ ] **Step 3: Commit**

```bash
cd /Users/cursor/Dev/dipalza/application_v2.0/dipalza_server
git add dipalza/src/main/java/cl/eos/dipalza/repository/NumeradoRepository.java
git commit -m "feat: agrega query de unicidad de número por producto en NumeradoRepository"
```

---

### Task 2: Validaciones + recálculo de `pieces` en `NumeradosService`

**Files:**
- Modify: `dipalza/src/main/java/cl/eos/dipalza/service/NumeradosService.java`
- Modify: `dipalza/src/main/resources/application.yml`
- Test: `dipalza/src/test/java/cl/eos/dipalza/service/NumeradosServiceTest.java`

**Interfaces:**
- Consumes: `NumeradoRepository.existsNumeroActivoParaProducto(String, Integer, Long)` (Task 1).
- Produces: `NumeradosService.save(NumeradoDTO)` ahora lanza `ResponseStatusException` (400) si el producto no es numerado o el número ya está activo para ese producto, y actualiza `Producto.pieces` tras guardar. `NumeradosService.deleteById(Long)` ahora lanza `ResponseStatusException` (404) si el id no existe, y actualiza `Producto.pieces` tras eliminar.

Este task también corrige un bug preexistente: `numeradoRepository.findById(n.getId())` se llamaba siempre, incluso cuando `n.getId()` es `null` (caso de alta) — `JpaRepository.findById(null)` lanza `IllegalArgumentException`, por lo que **crear un numerado nuevo vía `POST /api/numerados` falla hoy en ejecución real** (el mock de los tests existentes no lo detecta porque `any()` de Mockito acepta `null`). Se corrige con un guard explícito.

- [ ] **Step 1: Escribir los tests que fallan**

Reemplazar el contenido completo de `dipalza/src/test/java/cl/eos/dipalza/service/NumeradosServiceTest.java` por:

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

- [ ] **Step 2: Ejecutar los tests y verificar que fallan**

Run: `cd /Users/cursor/Dev/dipalza/application_v2.0/dipalza_server/dipalza && mvn -q test -Dtest=NumeradosServiceTest`
Expected: FAIL — no compila todavía (`ResponseStatusException` no se lanza, `deleteById` no acepta el flujo nuevo) o los asserts de piezas fallan.

- [ ] **Step 3: Implementar `NumeradosService`**

Reemplazar el contenido completo de `dipalza/src/main/java/cl/eos/dipalza/service/NumeradosService.java` por:

```java
package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.Numerado;
import cl.eos.dipalza.entity.Producto;
import cl.eos.dipalza.mapper.NumeradoMapper;
import cl.eos.dipalza.model.NumeradoDTO;
import cl.eos.dipalza.model.NumeradoResumenDTO;
import cl.eos.dipalza.repository.NumeradoRepository;
import cl.eos.dipalza.repository.ProductoRepository;
import cl.eos.dipalza.utils.Constants;
import org.springframework.data.repository.query.Param;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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
        return numerados.stream().map(numeradoMapper::toDTO).collect(Collectors.toList());
    }

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

    public void deleteById(Long id) {
        Numerado numerado = numeradoRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Numerado no encontrado"));
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
```

- [ ] **Step 4: Habilitar mensaje de error en las respuestas 4xx**

En `dipalza/src/main/resources/application.yml`, agregar `error.include-message` bajo el bloque `server:` existente:

```yaml
server:
  port: 8080
  error:
    include-message: always
  tomcat:
    relaxed-query-chars: '|,{,},[,],^,`,<,>,",%'
```

**Por qué:** sin esto, Spring Boot omite el campo `message` en el cuerpo JSON de cualquier respuesta de error (incluyendo las de `ResponseStatusException`), así que el mensaje específico ("Ya existe un numerado activo...") nunca llegaría al frontend — quedaría un 400 sin texto útil que mostrar. Es un cambio global (afecta a todos los endpoints), pero solo agrega información al cuerpo de error; ningún endpoint depende hoy de que el mensaje esté ausente.

- [ ] **Step 5: Ejecutar los tests y verificar que pasan**

Run: `cd /Users/cursor/Dev/dipalza/application_v2.0/dipalza_server/dipalza && mvn -q test -Dtest=NumeradosServiceTest`
Expected: PASS (todos los tests, incluidos los nuevos).

- [ ] **Step 6: Commit**

```bash
cd /Users/cursor/Dev/dipalza/application_v2.0/dipalza_server
git add dipalza/src/main/java/cl/eos/dipalza/service/NumeradosService.java \
        dipalza/src/main/resources/application.yml \
        dipalza/src/test/java/cl/eos/dipalza/service/NumeradosServiceTest.java
git commit -m "fix: NumeradosService valida producto numerado, número duplicado y recalcula pieces"
```

---

### Task 3: Tests de `NumeradosController` para las nuevas validaciones

**Files:**
- Modify: `dipalza/src/test/java/cl/eos/dipalza/controller/NumeradosControllerTest.java`

**Interfaces:**
- Consumes: `NumeradosService.save(NumeradoDTO)` lanzando `ResponseStatusException` (Task 2) — este test mockea el service, no lo ejecuta de verdad.

- [ ] **Step 1: Agregar los tests que fallan**

Agregar al final de la clase `NumeradosControllerTest` (antes del cierre `}` de la clase), y agregar los imports `org.springframework.http.HttpStatus` y `org.springframework.web.server.ResponseStatusException` junto a los imports existentes:

```java
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
```

```java
    @Test
    void createNumerado_productoNoNumerado_retorna400() throws Exception {
        NumeradoDTO d = dto(null);
        when(service.save(any())).thenThrow(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "El producto no está marcado como numerado"));

        mockMvc.perform(post("/api/numerados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(d)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createNumerado_numeroDuplicado_retorna400() throws Exception {
        NumeradoDTO d = dto(null);
        when(service.save(any())).thenThrow(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ya existe un numerado activo con ese número para este producto"));

        mockMvc.perform(post("/api/numerados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(d)))
                .andExpect(status().isBadRequest());
    }
```

- [ ] **Step 2: Ejecutar los tests y verificar que pasan**

Run: `cd /Users/cursor/Dev/dipalza/application_v2.0/dipalza_server/dipalza && mvn -q test -Dtest=NumeradosControllerTest`
Expected: PASS (7 tests, los 5 existentes + 2 nuevos).

- [ ] **Step 3: Ejecutar toda la suite del módulo para confirmar que nada se rompió**

Run: `cd /Users/cursor/Dev/dipalza/application_v2.0/dipalza_server/dipalza && mvn -q test`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit y push**

```bash
cd /Users/cursor/Dev/dipalza/application_v2.0/dipalza_server
git add dipalza/src/test/java/cl/eos/dipalza/controller/NumeradosControllerTest.java
git commit -m "test: cubre 400 de validaciones nuevas en NumeradosController"
git push -u origin docs/spec-formulario-numerados
```

---

## Frontend (`dipalza_web_client`)

### Task 4: Crear la rama y agregar métodos HTTP en `VentasService`

**Files:**
- Create branch: `feat/formulario-numerados` desde `main`
- Modify: `src/app/ventas/models/model.ts`
- Modify: `src/app/ventas/ventas.service.ts`

**Interfaces:**
- Produces: `NumeradoPayload` (interfaz), `VentasService.crearNumerado(payload)`, `VentasService.actualizarNumerado(payload)`, `VentasService.eliminarNumerado(id)`, `VentasService.obtainProductos()` — usados por Task 5 y Task 7.

- [ ] **Step 1: Crear la rama**

```bash
cd /Users/cursor/Dev/dipalza/application_v2.0/dipalza_web_client
git checkout main
git pull origin main
git checkout -b feat/formulario-numerados
```

- [ ] **Step 2: Agregar la interfaz `NumeradoPayload`**

En `src/app/ventas/models/model.ts`, justo después de la interfaz `Numerado` existente (después de su línea de cierre `}`), agregar:

```ts
export interface NumeradoPayload {
    id?: number;
    codigoProducto: string;
    numero: number;
    peso: number;
    estado?: string;
}
```

- [ ] **Step 3: Agregar los métodos a `VentasService`**

En `src/app/ventas/ventas.service.ts`:

1. Cambiar el import de modelos (línea 4) a:
```ts
import { Numerado, NumeradoPayload, NumeradoResumen, Producto, Venta, VentaDetalle, VentaFacturaResultado } from './models/model';
```

2. Agregar dos nuevas URLs junto a las existentes (después de `urlNumeradosResumen`):
```ts
  private urlNumerados = `${environment.apiUrl}/numerados`;
  private urlProductos = `${environment.apiUrl}/productos`;
```

3. Agregar los métodos al final de la clase, antes del cierre `}`:
```ts
  crearNumerado(payload: NumeradoPayload): Observable<Numerado> {
    return this.httpClient.post<Numerado>(this.urlNumerados, payload);
  }

  actualizarNumerado(payload: NumeradoPayload): Observable<Numerado> {
    return this.httpClient.put<Numerado>(this.urlNumerados, payload);
  }

  eliminarNumerado(id: number): Observable<void> {
    return this.httpClient.delete<void>(this.urlNumerados, { body: { id } });
  }

  obtainProductos(): Observable<Producto[]> {
    return this.httpClient.get<Producto[]>(this.urlProductos);
  }
```

- [ ] **Step 4: Verificar que compila**

Run: `cd /Users/cursor/Dev/dipalza/application_v2.0/dipalza_web_client && npx ng build`
Expected: compila sin errores de TypeScript (puede haber warnings de presupuesto de tamaño de bundle preexistentes, no relacionados).

- [ ] **Step 5: Commit**

```bash
git add src/app/ventas/models/model.ts src/app/ventas/ventas.service.ts
git commit -m "feat: agrega métodos CRUD de numerados y obtainProductos a VentasService"
```

---

### Task 5: `EdicionNumeradosComponent` real (alta y edición)

**Files:**
- Modify: `src/app/numerados/edicion-numerados/edicion-numerados.component.ts`
- Modify: `src/app/numerados/edicion-numerados/edicion-numerados.component.html`
- Modify: `src/app/numerados/edicion-numerados/edicion-numerados.component.spec.ts`

**Interfaces:**
- Consumes: `VentasService.obtainProductos()`, `crearNumerado()`, `actualizarNumerado()` (Task 4); interfaces `Numerado`, `NumeradoPayload`, `Producto` de `models/model.ts`.
- Produces: ruta `/numerados/formulario-numerado` (consumida por Task 6/7/8) espera recibir por `router.navigate(..., { state })` una de: `{ numerado: Numerado }` (modo edición) o `{ codigoProductoPreseleccionado: string }` (modo alta con producto precargado) o nada (alta libre).

- [ ] **Step 1: Implementar el componente**

Reemplazar el contenido completo de `src/app/numerados/edicion-numerados/edicion-numerados.component.ts` por:

```ts
import { Component, OnInit } from '@angular/core';
import { ReactiveFormsModule, FormGroup, Validators, FormControl } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Numerado, NumeradoPayload, Producto } from 'app/ventas/models/model';
import { VentasService } from 'app/ventas/ventas.service';

interface NumeradoForm {
  producto: Producto | null;
  numero: number;
  peso: number;
}

@Component({
  selector: 'app-edicion-numerados',
  imports: [ReactiveFormsModule],
  templateUrl: './edicion-numerados.component.html',
  styleUrl: './edicion-numerados.component.scss'
})
export class EdicionNumeradosComponent implements OnInit {
  form: FormGroup;

  productos: Producto[] = [];
  numeradoEnEdicion: Numerado | null = null;
  codigoProductoPreseleccionado: string | null = null;

  loading = false;
  error = '';
  success = '';

  constructor(private ventasService: VentasService, private router: Router) {
    const navigation = this.router.getCurrentNavigation();
    const state = navigation?.extras?.state;
    this.numeradoEnEdicion = (state?.['numerado'] as Numerado) ?? null;
    this.codigoProductoPreseleccionado = (state?.['codigoProductoPreseleccionado'] as string) ?? null;

    this.form = new FormGroup({
      producto: new FormControl<Producto | null>(null, Validators.required),
      numero: new FormControl<number | null>(null, [Validators.required, Validators.min(1)]),
      peso: new FormControl<number | null>(null, [Validators.required, Validators.min(0.001)])
    });
  }

  get esEdicion(): boolean {
    return this.numeradoEnEdicion !== null;
  }

  ngOnInit(): void {
    this.ventasService.obtainProductos().subscribe({
      next: (productos) => {
        this.productos = productos.filter(p => p.numbered === true);
        this.preseleccionarProducto();
      },
      error: () => {
        this.error = 'No se pudo cargar la lista de productos.';
      }
    });
  }

  private preseleccionarProducto(): void {
    const codigo = this.numeradoEnEdicion?.codigoProducto ?? this.codigoProductoPreseleccionado;
    if (!codigo) {
      return;
    }
    const producto = this.productos.find(p => p.articulo === codigo) ?? null;
    this.form.patchValue({ producto });

    if (this.esEdicion) {
      this.form.get('producto')?.disable();
      this.form.patchValue({
        numero: this.numeradoEnEdicion?.numero,
        peso: this.numeradoEnEdicion?.peso
      });
    }
  }

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const data = this.form.getRawValue() as NumeradoForm;
    const payload: NumeradoPayload = {
      id: this.numeradoEnEdicion?.id,
      codigoProducto: data.producto?.articulo ?? '',
      numero: data.numero,
      peso: data.peso,
      estado: this.numeradoEnEdicion?.estado
    };

    this.loading = true;
    this.error = '';
    this.success = '';

    const peticion = this.esEdicion
      ? this.ventasService.actualizarNumerado(payload)
      : this.ventasService.crearNumerado(payload);

    peticion.subscribe({
      next: () => {
        this.loading = false;
        this.success = this.esEdicion ? 'Numerado actualizado correctamente.' : 'Numerado creado correctamente.';
        this.router.navigate(['/numerados']);
      },
      error: (err: HttpErrorResponse) => {
        this.loading = false;
        this.error = err.status === 400 && err.error?.message
          ? err.error.message
          : 'No se pudo guardar el numerado. Intente nuevamente.';
      }
    });
  }
}
```

- [ ] **Step 2: Actualizar el template**

Reemplazar el contenido completo de `src/app/numerados/edicion-numerados/edicion-numerados.component.html` por:

```html
<div class="row">
  <div class="col-lg-6">
    <div class="card">
      <div class="card-header">
        <h4>{{ esEdicion ? 'Editar numerado' : 'Agregar numerado' }}</h4>
      </div>
      <div class="card-body">
        <form [formGroup]="form" (ngSubmit)="submit()">

          <div class="form-group">
            <label>Seleccione producto</label>
            <select formControlName="producto" class="form-control">
              <option [ngValue]="null">-- Seleccione --</option>
              @for (p of productos; track p.articulo) {
                <option [ngValue]="p">{{ p.descripcion }}</option>
              }
            </select>
            @if (form.get('producto')?.touched && form.get('producto')?.invalid) {
              <small style="color:red">Debe seleccionar un producto</small>
            }
          </div>

          <div class="form-group">
            <label>Número</label>
            <input type="number" formControlName="numero" class="form-control" placeholder="Ingrese número" />
            @if (form.get('numero')?.touched && form.get('numero')?.invalid) {
              <small style="color:red">Número inválido</small>
            }
          </div>

          <div class="form-group">
            <label>Peso (kg)</label>
            <input type="number" step="0.001" formControlName="peso" class="form-control" placeholder="Ej: 1.250" />
            @if (form.get('peso')?.touched && form.get('peso')?.invalid) {
              <small style="color:red">Peso inválido</small>
            }
          </div>

          @if (error) {
            <div class="alert alert-danger">{{ error }}</div>
          }
          @if (success) {
            <div class="alert alert-success">{{ success }}</div>
          }

          <button type="submit" class="btn btn-primary" [disabled]="form.invalid || loading">
            @if (loading) {
              <span class="spinner-border spinner-border-sm me-1"></span>
            }
            Guardar
          </button>
        </form>
      </div>
    </div>
  </div>
</div>
```

- [ ] **Step 3: Actualizar el spec para proveer `HttpClient`/`Router` de test**

Reemplazar el contenido completo de `src/app/numerados/edicion-numerados/edicion-numerados.component.spec.ts` por:

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';

import { EdicionNumeradosComponent } from './edicion-numerados.component';

describe('EdicionNumeradosComponent', () => {
  let component: EdicionNumeradosComponent;
  let fixture: ComponentFixture<EdicionNumeradosComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [EdicionNumeradosComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])]
    })
    .compileComponents();

    fixture = TestBed.createComponent(EdicionNumeradosComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('arranca en modo alta cuando no hay estado de navegación', () => {
    expect(component.esEdicion).toBeFalse();
  });
});
```

- [ ] **Step 4: Verificar que compila**

Run: `cd /Users/cursor/Dev/dipalza/application_v2.0/dipalza_web_client && npx ng build`
Expected: compila sin errores de TypeScript.

**Nota:** `ng test` no se puede ejecutar en este entorno (sin navegador headless) — correr `ng test` en la máquina local o CI antes de mergear para confirmar que el spec realmente pasa.

- [ ] **Step 5: Commit**

```bash
git add src/app/numerados/edicion-numerados/edicion-numerados.component.ts \
        src/app/numerados/edicion-numerados/edicion-numerados.component.html \
        src/app/numerados/edicion-numerados/edicion-numerados.component.spec.ts
git commit -m "feat: implementa el formulario real de alta/edición de numerados"
```

---

### Task 6: Ruta nueva y enlace desde el listado general

**Files:**
- Modify: `src/app/numerados/numerados.routes.ts`
- Modify: `src/app/numerados/listado-numerados/listado-numerados.component.ts`

**Interfaces:**
- Consumes: `EdicionNumeradosComponent` (Task 5).

- [ ] **Step 1: Agregar la ruta**

Reemplazar el contenido completo de `src/app/numerados/numerados.routes.ts` por:

```ts
import { Route } from '@angular/router';

export const NUMERADOS_ROUTES: Route[] = [
    {
        path: '',
        loadComponent: () => import('./listado-numerados/listado-numerados.component').then((m) => m.ListadoNumeradosComponent)
    },
    {
        path: 'detalle-numerado',
        loadComponent: () => import('./listado-numerados-de-un-producto/listado-numerados-de-un-producto.component').then((m) => m.ListadoNumeradosDeUnProductoComponent)
    },
    {
        path: 'formulario-numerado',
        loadComponent: () => import('./edicion-numerados/edicion-numerados.component').then((m) => m.EdicionNumeradosComponent)
    }
];
```

- [ ] **Step 2: Wirear el botón "Agregar" del listado general**

En `src/app/numerados/listado-numerados/listado-numerados.component.ts`, reemplazar el método `addNumerado()`:

```ts
  addNumerado() {
    this.router.navigate(['/numerados/formulario-numerado']);
  }
```

(reemplaza el cuerpo actual, que solo tenía un comentario).

- [ ] **Step 3: Verificar que compila**

Run: `cd /Users/cursor/Dev/dipalza/application_v2.0/dipalza_web_client && npx ng build`
Expected: compila sin errores de TypeScript.

- [ ] **Step 4: Commit**

```bash
git add src/app/numerados/numerados.routes.ts src/app/numerados/listado-numerados/listado-numerados.component.ts
git commit -m "feat: agrega la ruta del formulario de numerados y conecta el botón Agregar"
```

---

### Task 7: Acciones editar/eliminar/agregar en el detalle de un producto

**Files:**
- Modify: `src/app/numerados/listado-numerados-de-un-producto/listado-numerados-de-un-producto.component.ts`
- Modify: `src/app/numerados/listado-numerados-de-un-producto/listado-numerados-de-un-producto.component.html`

**Interfaces:**
- Consumes: `VentasService.eliminarNumerado(id)` (Task 4), ruta `/numerados/formulario-numerado` (Task 6), tipo `Numerado` de `models/model.ts`.

- [ ] **Step 1: Agregar los métodos al componente**

En `src/app/numerados/listado-numerados-de-un-producto/listado-numerados-de-un-producto.component.ts`, agregar estos tres métodos al final de la clase, antes del cierre `}`:

```ts
  agregarNumerado() {
    this.router.navigate(['/numerados/formulario-numerado'], {
      state: { codigoProductoPreseleccionado: this.numeradoResumenSeleccionado?.codigoProducto }
    });
  }

  editarNumerado(row: Numerado) {
    this.router.navigate(['/numerados/formulario-numerado'], {
      state: { numerado: row }
    });
  }

  eliminarNumerado(row: Numerado) {
    if (!confirm(`¿Eliminar el numerado ${row.numero}?`)) {
      return;
    }
    this.ventaService.eliminarNumerado(row.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.updateSalesByDate(this.numeradoResumenSeleccionado?.codigoProducto ?? ''),
        error: (error: HttpErrorResponse) => {
          console.error('Error al eliminar numerado:', error);
        }
      });
  }
```

- [ ] **Step 2: Agregar el botón "Agregar" y las acciones por fila en el template**

En `src/app/numerados/listado-numerados-de-un-producto/listado-numerados-de-un-producto.component.html`:

1. Reemplazar el bloque del buscador (la sección `<div class="form-group row">...</div>`, líneas 22-29 del archivo actual) por:

```html
                <div class="form-group row">
                    <div class="col-sm-4">
                        <div class="d-flex">
                            <label class="col-form-label msr-3">Buscar:</label>
                            <input type="text" class="form-control" (keyup)='updateFilter($event)'>
                        </div>
                    </div>
                    <div class="col-sm-auto ms-auto">
                        <button class="btn btn-primary" (click)="agregarNumerado()">
                            Agregar
                        </button>
                    </div>
                </div>
```

2. Reemplazar la columna de acciones existente (la última `<ngx-datatable-column>`, que hoy solo tiene el ícono de ver) por:

```html
                    <ngx-datatable-column name="" [sortable]="false" headerClass="align-center-header"
                        cellClass="align-center-cell">
                        <ng-template let-value="value" let-row="row" let-rowIndex="rowIndex"
                            ngx-datatable-cell-template>
                            <a class="msr-2 h-auto tblViewBtn" style="cursor: pointer;">
                                <i class="fas fa-eye"></i>
                            </a>
                            <a class="msr-2 h-auto tblEditBtn" (click)="editarNumerado(row)" style="cursor: pointer;">
                                <i class="fas fa-plus"></i>
                            </a>
                            <a class="msr-2 h-auto tblDelBtn" (click)="eliminarNumerado(row)" style="cursor: pointer;">
                                <i class="fas fa-minus"></i>
                            </a>
                        </ng-template>
                    </ngx-datatable-column>
```

- [ ] **Step 3: Verificar que compila**

Run: `cd /Users/cursor/Dev/dipalza/application_v2.0/dipalza_web_client && npx ng build`
Expected: compila sin errores de TypeScript.

- [ ] **Step 4: Commit y push**

```bash
git add src/app/numerados/listado-numerados-de-un-producto/listado-numerados-de-un-producto.component.ts \
        src/app/numerados/listado-numerados-de-un-producto/listado-numerados-de-un-producto.component.html
git commit -m "feat: agrega editar/eliminar/agregar numerado desde el detalle de un producto"
git push -u origin feat/formulario-numerados
```

---

## Verificación final manual (no automatizable en este entorno)

Con ambos backends corriendo en perfil `dev-sec` local (uno apuntando a una base de datos de prueba, no producción):

1. Alta desde el listado general (`/numerados` → Agregar) con un producto numerado → verificar que aparece en el detalle del producto y que `GET /api/productos` muestra `pieces` incrementado en 1.
2. Alta desde el detalle de un producto (`/numerados/detalle-numerado` → Agregar) → combo debe venir preseleccionado con ese producto, pero editable.
3. Intentar crear un numerado con un número ya usado por el mismo producto → debe rechazarse mostrando el mensaje de error en el formulario (no un fallo silencioso).
4. Editar un numerado existente (ícono editar en el detalle) → combo de producto deshabilitado, número/peso precargados; guardar y verificar que se actualizó.
5. Eliminar un numerado → confirmar, verificar que desaparece de la tabla y que `pieces` del producto bajó en 1.

**No verificado en este entorno:** ejecución real de `ng test` (Karma, requiere navegador) — correr en la máquina local o en CI antes de mergear.

---

## Fuera de alcance (ver spec)

- Tocar `Producto.Stock`.
- Estado "Anulada" como alternativa a eliminar.
- Cambiar el producto de un numerado ya existente durante la edición.
- Filtrado server-side por `numbered` en `/api/productos`.
