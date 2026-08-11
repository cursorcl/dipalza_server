# Gestión de productos elegibles para numerado

**Fecha:** 2026-08-10
**Repos afectados:** `dipalza_server`, `dipalza_web_client`
**Feature previa relacionada:** formulario de alta/edición/eliminación de
numerados individuales (`docs/superpowers/specs/2026-08-09-formulario-numerados-design.md`)

## Contexto y problema

Un producto solo puede tener numerados (piezas individuales con número y
peso) si el campo `Producto.numbered` (columna booleana en `producto`) está
en `true`. Hoy ese campo no se puede activar ni desactivar desde ninguna
pantalla de la app — quedó fuera del alcance del formulario de numerados
implementado el 2026-08-09, que asume el flag ya está en `true` y solo
gestiona los numerados individuales.

El listado general "Numerados Existentes" (`listado-numerados.component`,
ruta `/numerados`) muestra un resumen agrupado por producto a partir de la
tabla `Numerado` (`GET /api/numerados/resumen`), por lo que un producto
recién marcado como numerado y sin numerados creados aún no aparece ahí.

## Alcance

Se agrega la posibilidad de gestionar **qué productos son elegibles para
numerado** (activar/desactivar `Producto.numbered`), a través de un nuevo
botón "Gestionar" en el listado general, que abre un diálogo separado del
flujo existente de alta de numerados individuales.

**Fuera de alcance:**
- Crear el primer numerado de un producto recién marcado — se mantiene
  el botón "Agregar" existente sin cambios para ese flujo.
- Búsqueda de productos en el backend — se reutiliza el catálogo completo
  ya cargado en el cliente (mismo patrón que `EdicionNumeradosComponent`).
- Borrado físico de filas `Producto` — la tabla `producto` refleja el
  catálogo general (sincronizado externamente); "quitar" un producto de la
  lista de numerados solo desmarca el flag, nunca borra el producto.

## Backend (`dipalza_server`)

### Regla de negocio: "registros asociados"

Un producto puede desmarcarse (`numbered=false`) solo si no tiene **ningún**
`Numerado` asociado, en cualquier estado (Disponible, Reservado o Vendido).
Esto es más estricto que el criterio usado para recalcular `pieces` (que
solo cuenta estado Disponible) porque desmarcar no debe romper el historial
de numerados ya vendidos ni dejar huérfanos numerados reservados. Se
reutiliza `NumeradoRepository.findByProductoId(articulo)` para la
verificación (ya existe, sin cambios).

### DTO nuevo

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

### Endpoints nuevos (`NumeradosController`, prefijo `/api/numerados`)

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/productos-elegibles` | Lista productos con `numbered=true`: código, nombre, `pieces` (piezas), `stock` (peso), y `tieneRegistrosAsociados`. |
| `PUT` | `/productos-elegibles/{articulo}` | Marca `numbered=true`. Idempotente (si ya está en `true`, responde 200 sin error). 404 si el producto no existe. |
| `DELETE` | `/productos-elegibles/{articulo}` | Marca `numbered=false`. 404 si no existe. 400 (`ResponseStatusException`, mensaje vía el `@RestControllerAdvice` existente) si `tieneRegistrosAsociados`. |

Se agregan en `NumeradosService` (ya depende de `ProductoRepository`):

- `findProductosElegibles(): List<ProductoElegibleNumeradoDTO>`
- `marcarProductoComoNumerado(String articulo): void`
- `desmarcarProductoComoNumerado(String articulo): void` — valida
  `tieneRegistrosAsociados` antes de guardar.

No se reutiliza el `POST /api/productos` genérico (`ProductoService
.createOrUpdateProducto`) porque reescribiría el `Producto` completo
(precio, stock ERP, `rv` de versionado optimista) — riesgo de
desincronización con el catálogo sincronizado externamente. Los endpoints
nuevos tocan **solo** la columna `numbered`.

El selector de productos NO numerados (para "agregar") no requiere
endpoint nuevo: reutiliza `GET /api/productos` (ya existente, sin
cambios), filtrado en el cliente por `numbered !== true`.

### Testing

- `NumeradosServiceTest`: casos para `findProductosElegibles`,
  `marcarProductoComoNumerado` (producto inexistente → excepción; ya
  marcado → no-op), `desmarcarProductoComoNumerado` (con y sin registros
  asociados, incluyendo un numerado en estado Vendido para confirmar que
  el chequeo no se limita a Disponible).
- `NumeradosControllerTest`: casos 200/400/404 para los 3 endpoints
  nuevos.

## Frontend (`dipalza_web_client`)

### `listado-numerados.component`

Se agrega un botón "Gestionar" junto a "Actualizar" y "Agregar" (que se
mantienen sin cambios). Abre `GestionProductosNumeradosComponent` vía
`NgbModal`, mismo patrón que `abrirDialogoNumerado()`.

### Nuevo componente `GestionProductosNumeradosComponent`

Standalone, en `src/app/numerados/gestion-productos-numerados/`, estructura
análoga a `EdicionNumeradosComponent` (usa `NgbActiveModal`).

- **Buscador (arriba):** input con `NgbTypeahead` sobre los productos con
  `numbered !== true` obtenidos de `ventasService.obtainProductos()`
  (ya existente), filtrando por coincidencia parcial en código o en
  nombre (case-insensitive). Selecciona un producto y un botón "Agregar"
  llama a `agregarProductoElegibleNumerado(articulo)`; al responder OK,
  se quita de las opciones del buscador y se agrega a la tabla de abajo.
- **Tabla (abajo):** columnas Código, Nombre, Piezas, Stock (peso), y una
  columna de ícono de basurero a la derecha. El ícono está deshabilitado
  (con `title`/tooltip explicando el motivo) si `tieneRegistrosAsociados
  === true`. Al confirmar la eliminación, llama a
  `quitarProductoElegibleNumerado(articulo)`; si el backend rechaza (p.ej.
  carrera con un numerado creado mientras el diálogo estaba abierto), se
  muestra el mensaje de error igual que en `EdicionNumeradosComponent`
  (`err.error?.message` si `status === 400`, mensaje genérico si no).
- Al cerrar el diálogo no hace falta refrescar `listado-numerados`: esa
  tabla se arma desde numerados existentes (`/resumen`), no desde el flag
  `numbered`, así que no cambia por marcar/desmarcar productos.

### `ventas.service.ts`

```ts
obtainProductosElegiblesNumerado(): Observable<ProductoElegibleNumerado[]>
agregarProductoElegibleNumerado(articulo: string): Observable<void>
quitarProductoElegibleNumerado(articulo: string): Observable<void>
```

### `models/model.ts`

```ts
export interface ProductoElegibleNumerado {
  codigoProducto: string;
  nombreProducto: string;
  stock: number;
  piezas: number;
  tieneRegistrosAsociados: boolean;
}
```

### Testing

- Spec de `GestionProductosNumeradosComponent`: carga inicial, agregar
  producto (éxito), quitar producto habilitado (éxito), botón deshabilitado
  cuando `tieneRegistrosAsociados`, mensaje de error al fallar el DELETE.
- Ajuste menor en el spec de `listado-numerados.component` para el nuevo
  botón.

## Resumen de decisiones tomadas durante el brainstorming

- "Gestionar" administra el flag `numbered`, separado de gestionar
  numerados individuales (confirmado por el usuario).
- "Eliminar" en el diálogo desmarca (`numbered=false`), nunca borra la fila
  `Producto` (confirmado — el catálogo se sincroniza externamente).
- El botón "Agregar" existente se mantiene sin cambios (crea el primer
  numerado de un producto); "Gestionar" es un botón nuevo y separado.
- El buscador de productos no-numerados es un typeahead que busca por
  código o por nombre, sobre el catálogo completo ya cargado en el
  cliente (sin endpoint de búsqueda en el backend).
