# Últimas Ventas por Cliente (backend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Exponer un endpoint que devuelva las últimas 3 ventas cerradas (facturadas) de un cliente, reutilizando la misma query que ya usa "última venta" (`findVentasCerradasByClienteOrderByFechaDesc`), solo que con `PageRequest.of(0, 3)` y devolviendo una lista.

**Architecture:** Extensión mínima de `VentaService`/`VentaController`, calcada de `obtenerUltimaVentaDeCliente`/`POST /api/ventas/ultimaventacliente` ya existentes. No hay cambios de esquema ni de repositorio.

**Tech Stack:** Spring Boot 3.5.4, JPA (query ya existente en `VentaRepository`), JUnit 5 + Mockito + `MockMvc` (`@WebMvcTest`), mismo patrón que `VentaControllerTest`.

## Global Constraints

- Mismo criterio que "última venta": solo ventas con `estado = 'CLOSED'` (facturadas), más recientes primero.
- Cantidad fija en 3 (no configurable vía parámetro).
- Lista vacía es una respuesta válida (`200 OK` con `[]`) si el cliente no tiene ventas cerradas — a diferencia del endpoint singular, acá no hay caso "404 no encontrado".

Spec de referencia: `docs/superpowers/specs/2026-07-18-ultimas-ventas-cliente-design.md` (repo `flutterDipalza`, ya que es la spec compartida de todo el feature).

---

## File Structure

- **Modify:** `dipalza/src/main/java/cl/eos/dipalza/service/VentaService.java` — agrega `obtenerUltimasVentasDeCliente`.
- **Modify:** `dipalza/src/main/java/cl/eos/dipalza/controller/VentaController.java` — agrega `POST /api/ventas/ultimasventascliente`.
- **Test:** `dipalza/src/test/java/cl/eos/dipalza/controller/VentaControllerTest.java` — extiende con el test del nuevo endpoint.

Sin test de `VentaService` dedicado: no existe `VentaServiceTest.java` en este código (el método hermano `obtenerUltimaVentaDeCliente` tampoco lo tiene) — el `@WebMvcTest` de `VentaControllerTest` con `VentaService` mockeado cubre el contrato HTTP, que es lo que consume la app móvil.

---

## Task 1: `POST /api/ventas/ultimasventascliente`

**Files:**
- Modify: `dipalza/src/main/java/cl/eos/dipalza/service/VentaService.java:373` (justo después de `obtenerUltimaVentaDeCliente`)
- Modify: `dipalza/src/main/java/cl/eos/dipalza/controller/VentaController.java:130` (justo después de `obtenerUltimaVentaDeCliente`)
- Test: `dipalza/src/test/java/cl/eos/dipalza/controller/VentaControllerTest.java`

**Interfaces:**
- Produces: `VentaService.obtenerUltimasVentasDeCliente(ClienteIdQueryDTO params): List<VentaDTO>`; endpoint `POST /api/ventas/ultimasventascliente` con `@RequestBody ClienteIdQueryDTO`, devuelve `List<VentaDTO>` con `200 OK`.

- [ ] **Step 1: Escribir el test que falla**

En `dipalza/src/test/java/cl/eos/dipalza/controller/VentaControllerTest.java`, agregar el import que falta junto a los demás `cl.eos.dipalza.model.*`:

```java
import cl.eos.dipalza.model.ClienteIdQueryDTO;
```

Y agregar este test, al final de la clase (antes del `}` de cierre):

```java
    @Test
    void obtenerUltimasVentasDeCliente_retornaLista() throws Exception {
        ClienteIdQueryDTO body = new ClienteIdQueryDTO("11111111-1", "001");
        when(ventaService.obtenerUltimasVentasDeCliente(any()))
                .thenReturn(List.of(ventaDTO(1L), ventaDTO(2L)));

        mockMvc.perform(post("/api/ventas/ultimasventascliente")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `cd dipalza && ./mvnw test -Dtest=VentaControllerTest`
Expected: FAIL — no compila (`VentaService.obtenerUltimasVentasDeCliente` no existe todavía).

- [ ] **Step 3: Agregar el método al service**

En `dipalza/src/main/java/cl/eos/dipalza/service/VentaService.java`, agregar inmediatamente después de `obtenerUltimaVentaDeCliente` (después de la línea 373 en el estado actual del archivo):

```java
	public List<VentaDTO> obtenerUltimasVentasDeCliente(ClienteIdQueryDTO params) {
		Pageable p = PageRequest.of(0, 3); // últimas 3 ventas
		List<Venta> ventas = ventaRepository.findVentasCerradasByClienteOrderByFechaDesc(params.getRut(),
				params.getCodigo(), p);
		return ventas.stream().map(VentaMapper::toVentaDTO).toList();
	}
```

(No se necesitan imports nuevos: `PageRequest`, `Pageable`, `VentaMapper`, `ClienteIdQueryDTO`, `List`, `Venta` ya están importados en este archivo.)

- [ ] **Step 4: Agregar el endpoint al controller**

En `dipalza/src/main/java/cl/eos/dipalza/controller/VentaController.java`, agregar inmediatamente después de `obtenerUltimaVentaDeCliente` (después de la línea 130 en el estado actual del archivo):

```java
	// Obtener las últimas 3 ventas cerradas de un cliente
	@PostMapping("/ultimasventascliente")
	public ResponseEntity<List<VentaDTO>> obtenerUltimasVentasDeCliente(@RequestBody ClienteIdQueryDTO params) {
		List<VentaDTO> ultimasVentas = ventaService.obtenerUltimasVentasDeCliente(params);
		return new ResponseEntity<>(ultimasVentas, HttpStatus.OK);
	}
```

(No se necesitan imports nuevos: `List`, `ClienteIdQueryDTO`, `VentaDTO`, `HttpStatus`, `ResponseEntity` ya están importados en este archivo.)

- [ ] **Step 5: Ejecutar el test y confirmar que pasa**

Run: `cd dipalza && ./mvnw test -Dtest=VentaControllerTest`
Expected: PASS (todos los tests de la clase, incluyendo el nuevo).

- [ ] **Step 6: Ejecutar la suite completa del módulo**

Run: `cd dipalza && ./mvnw test`
Expected: BUILD SUCCESS — ningún otro test se ve afectado (cambio aditivo, sin tocar firmas existentes).

- [ ] **Step 7: Commit**

```bash
git add dipalza/src/main/java/cl/eos/dipalza/service/VentaService.java dipalza/src/main/java/cl/eos/dipalza/controller/VentaController.java dipalza/src/test/java/cl/eos/dipalza/controller/VentaControllerTest.java
git commit -m "feat: agrega endpoint para las últimas 3 ventas cerradas de un cliente"
```
