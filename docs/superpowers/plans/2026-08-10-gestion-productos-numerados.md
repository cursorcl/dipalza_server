# Gestión de productos elegibles para numerado — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permitir activar/desactivar el flag `Producto.numbered` desde un nuevo diálogo "Gestionar" en el listado de numerados, para poder agregar o quitar productos de la lista de elegibles para numerado.

**Architecture:** Backend: 2 endpoints REST angostos nuevos en `NumeradosController`/`NumeradosService` que tocan solo la columna `numbered` (no el `Producto` completo), más un DTO de resumen (`ProductoElegibleNumeradoDTO`). Frontend: un nuevo componente de diálogo Angular standalone (`GestionProductosNumeradosComponent`), abierto vía `NgbModal` desde `listado-numerados.component`, con un buscador `NgbTypeahead` (código o nombre) para agregar y una tabla con basurero para quitar.

**Tech Stack:** Spring Boot 3.5 / JPA (backend), Angular standalone components + `@ng-bootstrap/ng-bootstrap` 19 + `sweetalert2` (frontend). Testing: JUnit 5 + Mockito + AssertJ + MockMvc (backend), Karma/Jasmine (frontend).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-10-gestion-productos-numerados-design.md` (mismo repo `dipalza_server`).
- Nomenclatura en español para nombres de variables, mensajes de error y UI, salvo donde el archivo ya mezcla inglés (seguir el estilo del archivo que se está modificando).
- "Quitar" un producto de la lista de numerados **nunca** borra la fila `Producto` — solo pone `numbered=false`. La fila `Producto` la sincroniza un sistema externo.
- "Tiene registros asociados" se evalúa sobre **todos** los estados de `Numerado` (Disponible, Reservado, Vendido), no solo Disponible — es una regla más estricta que el recálculo de `pieces`.
- No se reutiliza `POST /api/productos` (`ProductoService.createOrUpdateProducto`) para esto: los endpoints nuevos tocan solo la columna `numbered`.
- El botón "Agregar" existente en `listado-numerados.component` (crea el primer numerado de un producto) se mantiene sin cambios. "Gestionar" es un botón nuevo y separado.
- El buscador de productos no-numerados es un typeahead client-side (código o nombre) sobre `GET /api/productos` ya existente — sin endpoint de búsqueda nuevo en el backend.
- Antes de cada tarea, verificar `git status`/rama activa: se trabaja en `feat/gestion-productos-numerados` en ambos repos (`dipalza_server`, `dipalza_web_client`), creada desde `main` ya actualizado.

---

## Task 1: Backend — reglas de negocio en `NumeradosService`

**Files:**
- Create: `dipalza/src/main/java/cl/eos/dipalza/model/ProductoElegibleNumeradoDTO.java`
- Modify: `dipalza/src/main/java/cl/eos/dipalza/repository/ProductoRepository.java`
- Modify: `dipalza/src/main/java/cl/eos/dipalza/repository/NumeradoRepository.java`
- Modify: `dipalza/src/main/java/cl/eos/dipalza/service/NumeradosService.java`
- Test: `dipalza/src/test/java/cl/eos/dipalza/service/NumeradosServiceTest.java`

**Interfaces:**
- Consumes: `Producto.getArticulo()/getDescripcion()/getStock()/getPieces()/getNumbered()/setNumbered(Boolean)` (ya existen en `Producto.java`).
- Produces (usado por Task 2):
  - `NumeradosService.findProductosElegibles(): List<ProductoElegibleNumeradoDTO>`
  - `NumeradosService.marcarProductoComoNumerado(String articulo): void` — lanza `ResponseStatusException(404)` si el producto no existe; no-op si ya está `numbered=true`.
  - `NumeradosService.desmarcarProductoComoNumerado(String articulo): void` — lanza `ResponseStatusException(404)` si no existe; lanza `ResponseStatusException(400, "No se puede quitar: el producto tiene numerados asociados")` si tiene registros asociados.
  - `ProductoElegibleNumeradoDTO(String codigoProducto, String nombreProducto, BigDecimal stock, BigDecimal piezas, boolean tieneRegistrosAsociados)` — record.

- [ ] **Step 1: Agregar el DTO y las firmas de repositorio (sin lógica todavía)**

Crear `dipalza/src/main/java/cl/eos/dipalza/model/ProductoElegibleNumeradoDTO.java`:

```java
package cl.eos.dipalza.model;

import java.math.BigDecimal;

public record ProductoElegibleNumeradoDTO(
        String codigoProducto,
        String nombreProducto,
        BigDecimal stock,
        BigDecimal piezas,
        boolean tieneRegistrosAsociados
) {}
```

En `dipalza/src/main/java/cl/eos/dipalza/repository/ProductoRepository.java`, agregar un método (Spring Data lo deriva automáticamente del nombre, sin `@Query`):

```java
	List<Producto> findByNumberedTrue();
```

(queda como el tercer método de la interfaz, junto a `getProductosByDescripcion` y `findByArticulo`).

En `dipalza/src/main/java/cl/eos/dipalza/repository/NumeradoRepository.java`, agregar (derivado, sin `@Query`, evita traer todas las filas solo para chequear existencia):

```java
    boolean existsByProducto_Articulo(String articulo);
```

- [ ] **Step 2: Escribir los tests que fallan para `NumeradosService`**

Agregar al final de la clase `NumeradosServiceTest` (antes del cierre `}`), reutilizando el helper `productoNumerado(String articulo)` ya existente en el archivo:

```java
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
```

Agregar el import que falta junto a los demás imports de `cl.eos.dipalza.model` en `NumeradosServiceTest.java`:

```java
import cl.eos.dipalza.model.ProductoElegibleNumeradoDTO;
```

- [ ] **Step 3: Ejecutar los tests y verificar que fallan**

Run: `cd dipalza && mvn test -Dtest=NumeradosServiceTest`
Expected: FALLA DE COMPILACIÓN — `NumeradosService` no tiene los métodos `findProductosElegibles`, `marcarProductoComoNumerado` ni `desmarcarProductoComoNumerado`.

- [ ] **Step 4: Implementar los métodos en `NumeradosService`**

En `dipalza/src/main/java/cl/eos/dipalza/service/NumeradosService.java`, agregar el import:

```java
import cl.eos.dipalza.model.ProductoElegibleNumeradoDTO;
```

Y agregar estos tres métodos públicos (después de `findGrouped()`, por ejemplo):

```java
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
```

- [ ] **Step 5: Ejecutar los tests y verificar que pasan**

Run: `cd dipalza && mvn test -Dtest=NumeradosServiceTest`
Expected: PASS (todos los tests, incluidos los preexistentes).

- [ ] **Step 6: Commit**

```bash
git add dipalza/src/main/java/cl/eos/dipalza/model/ProductoElegibleNumeradoDTO.java \
        dipalza/src/main/java/cl/eos/dipalza/repository/ProductoRepository.java \
        dipalza/src/main/java/cl/eos/dipalza/repository/NumeradoRepository.java \
        dipalza/src/main/java/cl/eos/dipalza/service/NumeradosService.java \
        dipalza/src/test/java/cl/eos/dipalza/service/NumeradosServiceTest.java
git commit -m "feat: agrega reglas de negocio para gestionar productos elegibles para numerado"
```

---

## Task 2: Backend — endpoints REST en `NumeradosController`

**Files:**
- Modify: `dipalza/src/main/java/cl/eos/dipalza/controller/NumeradosController.java`
- Test: `dipalza/src/test/java/cl/eos/dipalza/controller/NumeradosControllerTest.java`

**Interfaces:**
- Consumes: `NumeradosService.findProductosElegibles()`, `.marcarProductoComoNumerado(String)`, `.desmarcarProductoComoNumerado(String)` (Task 1), `ProductoElegibleNumeradoDTO` (Task 1).
- Produces (usado por Task 3 en el frontend):
  - `GET /api/numerados/productos-elegibles` → `200` con `List<ProductoElegibleNumeradoDTO>` (JSON: `codigoProducto`, `nombreProducto`, `stock`, `piezas`, `tieneRegistrosAsociados`).
  - `PUT /api/numerados/productos-elegibles/{articulo}` → `200` vacío; `404` si el producto no existe.
  - `DELETE /api/numerados/productos-elegibles/{articulo}` → `200` vacío; `404` si no existe; `400` con `{"message": "..."}` si tiene registros asociados (vía el `@RestControllerAdvice` ya existente, `ResponseStatusExceptionHandler`).

- [ ] **Step 1: Escribir los tests que fallan para el controller**

Agregar al final de `NumeradosControllerTest` (antes del cierre `}`):

```java
    @Test
    void getProductosElegibles_retornaLista() throws Exception {
        ProductoElegibleNumeradoDTO dto = new ProductoElegibleNumeradoDTO(
                "ART001", "Queso", BigDecimal.valueOf(50), BigDecimal.valueOf(3), false);
        when(service.findProductosElegibles()).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/numerados/productos-elegibles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].codigoProducto", is("ART001")))
                .andExpect(jsonPath("$[0].tieneRegistrosAsociados", is(false)));
    }

    @Test
    void marcarProductoElegible_ok_retorna200() throws Exception {
        mockMvc.perform(put("/api/numerados/productos-elegibles/ART001"))
                .andExpect(status().isOk());

        verify(service).marcarProductoComoNumerado("ART001");
    }

    @Test
    void marcarProductoElegible_productoNoExiste_retorna404() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Producto no encontrado"))
                .when(service).marcarProductoComoNumerado("NOEXISTE");

        mockMvc.perform(put("/api/numerados/productos-elegibles/NOEXISTE"))
                .andExpect(status().isNotFound());
    }

    @Test
    void desmarcarProductoElegible_ok_retorna200() throws Exception {
        mockMvc.perform(delete("/api/numerados/productos-elegibles/ART001"))
                .andExpect(status().isOk());

        verify(service).desmarcarProductoComoNumerado("ART001");
    }

    @Test
    void desmarcarProductoElegible_conRegistrosAsociados_retorna400() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "No se puede quitar: el producto tiene numerados asociados"))
                .when(service).desmarcarProductoComoNumerado("ART001");

        mockMvc.perform(delete("/api/numerados/productos-elegibles/ART001"))
                .andExpect(status().isBadRequest());
    }
```

Agregar el import que falta junto a los demás imports de `cl.eos.dipalza.model`:

```java
import cl.eos.dipalza.model.ProductoElegibleNumeradoDTO;
```

- [ ] **Step 2: Ejecutar los tests y verificar que fallan**

Run: `cd dipalza && mvn test -Dtest=NumeradosControllerTest`
Expected: FALLA — los endpoints `/productos-elegibles` no existen todavía (404 en vez de 200/400).

- [ ] **Step 3: Implementar los endpoints en `NumeradosController`**

En `dipalza/src/main/java/cl/eos/dipalza/controller/NumeradosController.java`, agregar el import:

```java
import cl.eos.dipalza.model.ProductoElegibleNumeradoDTO;
```

Y agregar estos tres endpoints (por ejemplo, después de `getGroupedNumerados()`):

```java
    @GetMapping("/productos-elegibles")
    public List<ProductoElegibleNumeradoDTO> getProductosElegibles() {
        return this.numeradosService.findProductosElegibles();
    }

    @PutMapping("/productos-elegibles/{articulo}")
    public void marcarProductoElegible(@PathVariable String articulo) {
        this.numeradosService.marcarProductoComoNumerado(articulo);
    }

    @DeleteMapping("/productos-elegibles/{articulo}")
    public void desmarcarProductoElegible(@PathVariable String articulo) {
        this.numeradosService.desmarcarProductoComoNumerado(articulo);
    }
```

Agregar el import de `@PathVariable` si no está cubierto por el `import org.springframework.web.bind.annotation.*;` ya existente (ya lo cubre — no hace falta cambio adicional).

- [ ] **Step 4: Ejecutar los tests y verificar que pasan**

Run: `cd dipalza && mvn test -Dtest=NumeradosControllerTest`
Expected: PASS.

- [ ] **Step 5: Ejecutar toda la suite de backend**

Run: `cd dipalza && mvn test`
Expected: `BUILD SUCCESS`, sin regresiones.

- [ ] **Step 6: Commit**

```bash
git add dipalza/src/main/java/cl/eos/dipalza/controller/NumeradosController.java \
        dipalza/src/test/java/cl/eos/dipalza/controller/NumeradosControllerTest.java
git commit -m "feat: expone endpoints REST para gestionar productos elegibles para numerado"
```

---

## Task 3: Frontend — modelo, servicio y diálogo `GestionProductosNumeradosComponent`

**Files:**
- Modify: `src/app/ventas/models/model.ts`
- Modify: `src/app/ventas/ventas.service.ts`
- Create: `src/app/numerados/gestion-productos-numerados/gestion-productos-numerados.component.ts`
- Create: `src/app/numerados/gestion-productos-numerados/gestion-productos-numerados.component.html`
- Create: `src/app/numerados/gestion-productos-numerados/gestion-productos-numerados.component.scss` (vacío, mismo patrón que `edicion-numerados.component.scss`)
- Test: `src/app/numerados/gestion-productos-numerados/gestion-productos-numerados.component.spec.ts`

**Interfaces:**
- Consumes: `VentasService` (patrón existente, `src/app/ventas/ventas.service.ts`), `Producto` (`model.ts`, ya existe: `articulo`, `descripcion`, `numbered`), endpoints de Task 2.
- Produces (usado por Task 4):
  - `GestionProductosNumeradosComponent` (standalone, selector `app-gestion-productos-numerados`), abierto vía `NgbModal`.
  - `VentasService.obtainProductosElegiblesNumerado(): Observable<ProductoElegibleNumerado[]>`
  - `VentasService.agregarProductoElegibleNumerado(articulo: string): Observable<void>`
  - `VentasService.quitarProductoElegibleNumerado(articulo: string): Observable<void>`
  - `interface ProductoElegibleNumerado { codigoProducto: string; nombreProducto: string; stock: number; piezas: number; tieneRegistrosAsociados: boolean; }`

- [ ] **Step 1: Agregar el modelo `ProductoElegibleNumerado`**

En `src/app/ventas/models/model.ts`, agregar al final del archivo:

```ts
export interface ProductoElegibleNumerado {
    codigoProducto: string;
    nombreProducto: string;
    stock: number;
    piezas: number;
    tieneRegistrosAsociados: boolean;
}
```

- [ ] **Step 2: Agregar los métodos al `VentasService`**

En `src/app/ventas/ventas.service.ts`:

Agregar `ProductoElegibleNumerado` al import existente de `./models/model` (línea 4):

```ts
import { Numerado, NumeradoPayload, NumeradoResumen, Producto, ProductoElegibleNumerado, Venta, VentaDetalle, VentaFacturaResultado } from './models/model';
```

Agregar la URL base junto a `urlProductos` (línea ~20):

```ts
  private urlProductosElegibles = `${environment.apiUrl}/numerados/productos-elegibles`;
```

Agregar los tres métodos al final de la clase, después de `obtainProductos()`:

```ts
  obtainProductosElegiblesNumerado(): Observable<ProductoElegibleNumerado[]> {
    return this.httpClient.get<ProductoElegibleNumerado[]>(this.urlProductosElegibles);
  }

  agregarProductoElegibleNumerado(articulo: string): Observable<void> {
    return this.httpClient.put<void>(`${this.urlProductosElegibles}/${articulo}`, {});
  }

  quitarProductoElegibleNumerado(articulo: string): Observable<void> {
    return this.httpClient.delete<void>(`${this.urlProductosElegibles}/${articulo}`);
  }
```

- [ ] **Step 3: Escribir el spec (que falla) de `GestionProductosNumeradosComponent`**

Crear `src/app/numerados/gestion-productos-numerados/gestion-productos-numerados.component.spec.ts`:

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { NgbActiveModal } from '@ng-bootstrap/ng-bootstrap';
import { environment } from 'environments/environment';

import { GestionProductosNumeradosComponent } from './gestion-productos-numerados.component';
import { Producto, ProductoElegibleNumerado } from 'app/ventas/models/model';

describe('GestionProductosNumeradosComponent', () => {
  let component: GestionProductosNumeradosComponent;
  let fixture: ComponentFixture<GestionProductosNumeradosComponent>;
  let httpMock: HttpTestingController;

  const productoElegible: ProductoElegibleNumerado = {
    codigoProducto: 'ART001',
    nombreProducto: 'Queso',
    stock: 50,
    piezas: 3,
    tieneRegistrosAsociados: false
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [GestionProductosNumeradosComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), NgbActiveModal]
    })
    .compileComponents();

    fixture = TestBed.createComponent(GestionProductosNumeradosComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    httpMock.expectOne(`${environment.apiUrl}/numerados/productos-elegibles`).flush([productoElegible]);
    httpMock.expectOne(`${environment.apiUrl}/productos`).flush([]);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('carga los productos elegibles al iniciar', () => {
    expect(component.rows).toEqual([productoElegible]);
  });

  it('no permite quitar un producto con registros asociados (no llama al backend)', () => {
    const conRegistros: ProductoElegibleNumerado = { ...productoElegible, tieneRegistrosAsociados: true };

    component.quitarProducto(conRegistros);

    httpMock.expectNone(`${environment.apiUrl}/numerados/productos-elegibles/${conRegistros.codigoProducto}`);
  });

  it('agrega el producto seleccionado y refresca ambas listas', () => {
    const producto: Producto = { articulo: 'ART002', descripcion: 'Jamón', numbered: false } as Producto;
    component.productoSeleccionado = producto;

    component.agregarProducto();

    const req = httpMock.expectOne(`${environment.apiUrl}/numerados/productos-elegibles/ART002`);
    expect(req.request.method).toBe('PUT');
    req.flush(null);

    httpMock.expectOne(`${environment.apiUrl}/numerados/productos-elegibles`).flush([productoElegible]);
    httpMock.expectOne(`${environment.apiUrl}/productos`).flush([]);

    expect(component.productoSeleccionado).toBeNull();
  });
});
```

- [ ] **Step 4: Ejecutar el test y verificar que falla**

Run: `ng test --include='**/gestion-productos-numerados.component.spec.ts'`
Expected: FALLA — el módulo `./gestion-productos-numerados.component` no existe.

- [ ] **Step 5: Crear el componente**

Crear `src/app/numerados/gestion-productos-numerados/gestion-productos-numerados.component.scss` (vacío).

Crear `src/app/numerados/gestion-productos-numerados/gestion-productos-numerados.component.ts`:

```ts
import { Component, OnInit } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ReactiveFormsModule, FormControl } from '@angular/forms';
import { NgbActiveModal, NgbTypeahead, NgbTypeaheadSelectItemEvent } from '@ng-bootstrap/ng-bootstrap';
import { Observable, OperatorFunction, debounceTime, distinctUntilChanged, map } from 'rxjs';
import Swal from 'sweetalert2';
import { Producto, ProductoElegibleNumerado } from 'app/ventas/models/model';
import { VentasService } from 'app/ventas/ventas.service';

@Component({
  selector: 'app-gestion-productos-numerados',
  imports: [ReactiveFormsModule, NgbTypeahead],
  templateUrl: './gestion-productos-numerados.component.html',
  styleUrl: './gestion-productos-numerados.component.scss'
})
export class GestionProductosNumeradosComponent implements OnInit {
  rows: ProductoElegibleNumerado[] = [];
  productosDisponibles: Producto[] = [];
  productoSeleccionado: Producto | null = null;
  buscadorControl = new FormControl('');

  loading = false;
  agregando = false;
  error = '';

  constructor(public activeModal: NgbActiveModal, private ventasService: VentasService) {}

  ngOnInit(): void {
    this.cargarProductosElegibles();
    this.cargarProductosDisponibles();
  }

  buscarProducto: OperatorFunction<string, readonly Producto[]> = (text$: Observable<string>) =>
    text$.pipe(
      debounceTime(200),
      distinctUntilChanged(),
      map(term => {
        const t = term.toLowerCase().trim();
        if (t.length < 2) {
          return [];
        }
        return this.productosDisponibles
          .filter(p => p.articulo.toLowerCase().includes(t) || p.descripcion.toLowerCase().includes(t))
          .slice(0, 10);
      })
    );

  formatearProducto = (p: Producto): string => p ? `${p.articulo} - ${p.descripcion}` : '';

  seleccionarProducto(event: NgbTypeaheadSelectItemEvent<Producto>): void {
    this.productoSeleccionado = event.item;
  }

  agregarProducto(): void {
    if (!this.productoSeleccionado) {
      return;
    }
    this.error = '';
    this.agregando = true;
    const articulo = this.productoSeleccionado.articulo;
    this.ventasService.agregarProductoElegibleNumerado(articulo).subscribe({
      next: () => {
        this.agregando = false;
        this.productoSeleccionado = null;
        this.buscadorControl.setValue('');
        this.cargarProductosElegibles();
        this.cargarProductosDisponibles();
      },
      error: (err: HttpErrorResponse) => {
        this.agregando = false;
        this.error = err.status === 400 && err.error?.message
          ? err.error.message
          : 'No se pudo agregar el producto a la lista de numerados.';
      }
    });
  }

  quitarProducto(row: ProductoElegibleNumerado): void {
    if (row.tieneRegistrosAsociados) {
      return;
    }
    Swal.fire({
      title: 'Quitar producto numerado',
      text: `¿Quitar ${row.nombreProducto} (${row.codigoProducto}) de la lista de productos numerados?`,
      icon: 'warning',
      showCancelButton: true,
      confirmButtonText: 'Sí',
      cancelButtonText: 'No',
      confirmButtonColor: '#d33'
    }).then((result) => {
      if (!result.isConfirmed) {
        return;
      }
      this.error = '';
      this.ventasService.quitarProductoElegibleNumerado(row.codigoProducto).subscribe({
        next: () => {
          this.cargarProductosElegibles();
          this.cargarProductosDisponibles();
        },
        error: (err: HttpErrorResponse) => {
          this.error = err.status === 400 && err.error?.message
            ? err.error.message
            : 'No se pudo quitar el producto de la lista de numerados.';
        }
      });
    });
  }

  private cargarProductosElegibles(): void {
    this.loading = true;
    this.ventasService.obtainProductosElegiblesNumerado().subscribe({
      next: (rows) => {
        this.rows = rows;
        this.loading = false;
      },
      error: () => {
        this.error = 'No se pudo cargar la lista de productos numerados.';
        this.loading = false;
      }
    });
  }

  private cargarProductosDisponibles(): void {
    this.ventasService.obtainProductos().subscribe({
      next: (productos) => {
        this.productosDisponibles = productos.filter(p => p.numbered !== true);
      },
      error: () => {
        this.error = 'No se pudo cargar el catálogo de productos.';
      }
    });
  }
}
```

Crear `src/app/numerados/gestion-productos-numerados/gestion-productos-numerados.component.html`:

```html
<div class="modal-header">
  <h5 class="modal-title">Gestionar productos numerados</h5>
  <button type="button" class="btn-close" aria-label="Cerrar" (click)="activeModal.dismiss()"></button>
</div>

<div class="modal-body">
  @if (error) {
    <div class="alert alert-danger">{{ error }}</div>
  }

  <div class="form-group d-flex gap-2 align-items-end mb-3">
    <div class="flex-grow-1">
      <label>Agregar producto (busque por código o nombre)</label>
      <input type="text" class="form-control" [formControl]="buscadorControl"
        [ngbTypeahead]="buscarProducto" [resultFormatter]="formatearProducto"
        [inputFormatter]="formatearProducto" (selectItem)="seleccionarProducto($event)"
        placeholder="Ej: ART001 o Queso" />
    </div>
    <button type="button" class="btn btn-primary" [disabled]="!productoSeleccionado || agregando"
      (click)="agregarProducto()">
      @if (agregando) {
        <span class="spinner-border spinner-border-sm me-1"></span>
      }
      Agregar
    </button>
  </div>

  @if (loading) {
    <div>Cargando...</div>
  } @else {
    <table class="table">
      <thead>
        <tr>
          <th>Código</th>
          <th>Nombre</th>
          <th>Piezas</th>
          <th>Stock (peso)</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        @for (row of rows; track row.codigoProducto) {
          <tr>
            <td>{{ row.codigoProducto }}</td>
            <td>{{ row.nombreProducto }}</td>
            <td>{{ row.piezas }}</td>
            <td>{{ row.stock }}</td>
            <td>
              <a class="tblDelBtn"
                [style.cursor]="row.tieneRegistrosAsociados ? 'not-allowed' : 'pointer'"
                [style.opacity]="row.tieneRegistrosAsociados ? 0.4 : 1"
                [attr.title]="row.tieneRegistrosAsociados ? 'No se puede quitar: tiene numerados asociados' : 'Quitar de la lista de numerados'"
                (click)="quitarProducto(row)">
                <i class="fas fa-trash"></i>
              </a>
            </td>
          </tr>
        } @empty {
          <tr>
            <td colspan="5">No hay productos numerados.</td>
          </tr>
        }
      </tbody>
    </table>
  }
</div>

<div class="modal-footer">
  <button type="button" class="btn btn-secondary" (click)="activeModal.dismiss()">Cerrar</button>
</div>
```

- [ ] **Step 6: Ejecutar el test y verificar que pasa**

Run: `ng test --include='**/gestion-productos-numerados.component.spec.ts'`
Expected: PASS (4 tests).

- [ ] **Step 7: Commit**

```bash
git add src/app/ventas/models/model.ts \
        src/app/ventas/ventas.service.ts \
        src/app/numerados/gestion-productos-numerados
git commit -m "feat: agrega el diálogo para gestionar productos elegibles para numerado"
```

---

## Task 4: Frontend — botón "Gestionar" en `listado-numerados.component`

**Files:**
- Modify: `src/app/numerados/listado-numerados/listado-numerados.component.ts`
- Modify: `src/app/numerados/listado-numerados/listado-numerados.component.html`
- Modify: `src/app/numerados/listado-numerados/listado-numerados.component.spec.ts`

**Interfaces:**
- Consumes: `GestionProductosNumeradosComponent` (Task 3).
- Produces: `ListadoNumeradosComponent.gestionarProductosNumerados(): void` — abre el diálogo vía `NgbModal`.

- [ ] **Step 1: Escribir el test que falla**

Reemplazar el contenido completo de `src/app/numerados/listado-numerados/listado-numerados.component.spec.ts`:

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { NgbModal } from '@ng-bootstrap/ng-bootstrap';
import { of } from 'rxjs';

import { ListadoNumeradosComponent } from './listado-numerados.component';
import { GestionProductosNumeradosComponent } from '../gestion-productos-numerados/gestion-productos-numerados.component';

describe('ListadoNumeradosComponent', () => {
  let component: ListadoNumeradosComponent;
  let fixture: ComponentFixture<ListadoNumeradosComponent>;
  let modalSpy: jasmine.SpyObj<NgbModal>;

  beforeEach(async () => {
    modalSpy = jasmine.createSpyObj('NgbModal', ['open']);
    modalSpy.open.and.returnValue({ closed: of(undefined) } as any);

    await TestBed.configureTestingModule({
      imports: [ListadoNumeradosComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: NgbModal, useValue: modalSpy }
      ]
    })
    .compileComponents();

    fixture = TestBed.createComponent(ListadoNumeradosComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('gestionarProductosNumerados abre el diálogo de gestión de productos numerados', () => {
    component.gestionarProductosNumerados();

    expect(modalSpy.open).toHaveBeenCalledWith(GestionProductosNumeradosComponent, jasmine.any(Object));
  });
});
```

- [ ] **Step 2: Ejecutar el test y verificar que falla**

Run: `ng test --include='**/listado-numerados.component.spec.ts'`
Expected: FALLA — `gestionarProductosNumerados` no existe en `ListadoNumeradosComponent`.

- [ ] **Step 3: Implementar el botón**

En `src/app/numerados/listado-numerados/listado-numerados.component.ts`, agregar el import:

```ts
import { GestionProductosNumeradosComponent } from '../gestion-productos-numerados/gestion-productos-numerados.component';
```

Y agregar el método, después de `agregarNumerado()`:

```ts
  gestionarProductosNumerados() {
    this.modalService.open(GestionProductosNumeradosComponent, { size: 'lg' });
  }
```

En `src/app/numerados/listado-numerados/listado-numerados.component.html`, agregar el botón nuevo junto a "Agregar" (líneas 22-29 actuales):

```html
                    <div class="col-sm-auto ms-auto">
                        <div class="d-flex justify-content-end gap-2">
                            <button class="btn btn-primary" (click)="updateSalesByDate()">Actualizar</button>
                            <button class="btn btn-primary" (click)="addNumerado()">
                                Agregar
                            </button>
                            <button class="btn btn-primary" (click)="gestionarProductosNumerados()">
                                Gestionar
                            </button>
                        </div>
                    </div>
```

- [ ] **Step 4: Ejecutar el test y verificar que pasa**

Run: `ng test --include='**/listado-numerados.component.spec.ts'`
Expected: PASS (2 tests).

- [ ] **Step 5: Ejecutar toda la suite de frontend**

Run: `ng test --watch=false`
Expected: sin regresiones respecto al baseline conocido (125/144 en `main` al momento de escribir este plan — confirmar el número exacto de fallos preexistentes antes de comparar, dado que pudo cambiar).

- [ ] **Step 6: Commit**

```bash
git add src/app/numerados/listado-numerados
git commit -m "feat: agrega el botón Gestionar al listado de numerados"
```

---

## Self-Review (completado durante la escritura del plan)

**Cobertura del spec:**
- "Puedo agregar o quitar productos de la lista de numerados" → Task 1 (backend) + Task 3 (diálogo, agregar/quitar).
- "Botón Agregar cambia a Gestionar" → decisión revertida durante brainstorming: Agregar se mantiene, Gestionar se agrega aparte (Task 4). Reflejado en Global Constraints.
- "Diálogo con código, nombre, piezas y stock (peso)" → tabla en Task 3 (HTML).
- "Basurero a la derecha, habilitado solo sin registros asociados" → Task 3 (backend valida en Task 1, frontend deshabilita visualmente y hace guard en `quitarProducto`).
- "Buscar por código o por nombre" → `NgbTypeahead` en Task 3.

**Placeholders:** ninguno — todos los pasos incluyen código completo.

**Consistencia de tipos:** `ProductoElegibleNumeradoDTO` (backend) y `ProductoElegibleNumerado` (frontend) tienen los mismos 5 campos con los mismos nombres (Jackson serializa el record por sus accessors sin sufijo `get`, coincide con los nombres de la interfaz TS). Los nombres de métodos de `VentasService` y `NumeradosService` se usan igual en Task 3/4 que como se definieron en Task 1/2.
