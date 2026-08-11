# Formulario de productos numerados (backend + frontend)

**Fecha:** 2026-08-09
**Repos afectados:** `dipalza_server` (backend) y `dipalza_web_client` (frontend Angular).
**Motivación:** hoy no existe forma real de registrar/editar/eliminar una pieza numerada desde el sistema web. `NumeradosController`/`NumeradosService` exponen los endpoints, pero `NumeradosService.save()` no actualiza `Producto.pieces` (usado por `VentaItemProcessorNumerado` para saber cuántas piezas hay disponibles para vender), y `edicion-numerados.component.ts` en `dipalza_web_client` es un stub (`ngOnInit` lanza `Method not implemented`, `submit()` solo hace `console.log`, sin ruta ni botones que lo invoquen).

## Alcance

Se toca únicamente `Producto.pieces`, no `Producto.Stock`. `Stock` (kg) solo lo lee `VentaItemPorcessorNoNumerado` (productos no numerados) y se sincroniza de forma independiente desde el pipeline de movimientos de Mastersoft — no tiene relación con el flujo de numerados y no debe tocarse aquí.

## Backend (`dipalza_server`)

### `NumeradoRepository` — nueva query

```java
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
```

Un numerado en estado `V` (vendido) no bloquea reutilizar ese número — es historial, no ocupa la posición.

### `NumeradosService.save(NumeradoDTO)`

Antes de persistir:

1. Si `producto.getNumbered()` es `false` → `ResponseStatusException(BAD_REQUEST, "El producto no está marcado como numerado")`.
2. Si `numeradoRepository.existsNumeroActivoParaProducto(articulo, numero, id)` → `ResponseStatusException(BAD_REQUEST, "Ya existe un numerado activo con ese número para este producto")`.

Después de persistir el `Numerado`, recalcula y guarda:

```java
private void actualizarPiezasDisponibles(Producto producto) {
    int piezas = numeradoRepository
        .findByProductoIdAndEstadoOrderById(producto.getArticulo(), Constants.ESTADO_NUMERADO_DISPONIBLE)
        .size();
    producto.setPieces(BigDecimal.valueOf(piezas));
    productoRepository.save(producto);
}
```

Recalcular por conteo (en vez de sumar/restar delta) evita que `pieces` quede desincronizado ante cualquier edición futura — es el mismo criterio que ya usa `GET /api/numerados/resumen`.

El caso existente "producto no existe" (`productoRepository.findByArticulo(...) == null` → retorna `null`) no se toca: es un caso preexistente que no debería poder ocurrir desde este formulario (el producto siempre viene de un combo real), y cambiar su contrato rompería el test `save_productoNoExiste_retornaNull` sin necesidad real.

### `NumeradosService.deleteById(Long)`

Pasa a buscar el `Numerado` primero (para conocer su producto), eliminarlo, y llamar `actualizarPiezasDisponibles`. Sigue siendo eliminación física — no se introduce un estado "Anulada" en este flujo.

### Tests

- `NumeradosServiceTest`: casos nuevos para producto no numerado, número duplicado, y verificación de que `productoRepository.save` se invoca con el `pieces` recalculado tras `save()` y tras `deleteById()`.
- `NumeradosControllerTest`: verificar que las excepciones de validación se traducen a `400`.

## Frontend (`dipalza_web_client`)

### `VentasService`

Nuevos métodos: `crearNumerado`/`actualizarNumerado` (POST/PUT `/api/numerados`), `eliminarNumerado` (DELETE `/api/numerados`, body `{id}`), `obtainProductos()` (GET `/api/productos`, tipado con la interfaz `Producto` de `ventas/models/model.ts`, que ya incluye `numbered`).

### `edicion-numerados.component.ts`

- `ngOnInit`: carga productos vía `obtainProductos()` y filtra `numbered === true` para el combo. Lee `router.getCurrentNavigation()?.extras?.state?.['numerado']` (mismo patrón que usa hoy `listado-numerados-de-un-producto` para recibir datos por navegación): si viene, es edición — combo de producto deshabilitado, y se busca en la lista cargada el producto cuyo `articulo` coincida con `numerado.codigoProducto` para preseleccionarlo; `numero`/`peso` se precargan también. Si en cambio viene `state['codigoProductoPreseleccionado']` (string, ver más abajo), se busca ese código en la lista cargada y se preselecciona el combo en modo alta, sin deshabilitarlo.
- `submit()`: arma el payload (`id` solo si es edición), llama `crearNumerado`/`actualizarNumerado` según corresponda. Maneja `loading`/`error`/`success` con el mismo patrón visual que `perfil/cambiar-clave.component` (campos en el componente + `alert-danger`/`alert-success` en el template) — es el único precedente real de formulario con manejo de errores en este código base. En éxito, navega de vuelta a `/numerados`.

### `numerados.routes.ts`

Nueva ruta `formulario-numerado` → `EdicionNumeradosComponent`.

### `listado-numerados.component.ts` (resumen por producto)

El botón "Agregar" (hoy sin acción) navega a `formulario-numerado` sin `state` (alta libre).

### `listado-numerados-de-un-producto.component` (detalle de un producto)

Hoy sus filas solo tienen un ícono "ver" sin acción. Se agregan:
- Ícono editar → navega a `formulario-numerado` con `state: { numerado: row }`.
- Ícono eliminar → `confirm()` nativo + `eliminarNumerado(row.id)`, refresca la tabla en éxito.
- Botón "Agregar" en la cabecera de esta vista → navega a `formulario-numerado` con `state: { codigoProductoPreseleccionado: numeradoResumenSeleccionado.codigoProducto }` (combo precargado pero editable, ya que aquí sí se conoce el producto de contexto pero no hay razón para bloquearlo).

## Testing

- Backend: `mvn test`.
- Frontend: `ng test`.
- Manual contra `dev-sec` local: alta desde el listado general, alta desde el detalle de un producto (combo preseleccionado), edición, intento de número duplicado (debe rechazarse con mensaje visible), eliminación, y verificación de que `producto.pieces` en `GET /api/productos` queda correcto tras cada operación.

## Fuera de alcance

- Tocar `Producto.Stock` (kg) — pertenece al pipeline de sincronización con Mastersoft, no a este flujo.
- Estado "Anulada" como alternativa a eliminar (se mantiene eliminación física).
- Cambiar el producto de un numerado ya existente durante la edición.
- Endpoint de filtrado server-side por `numbered` en `/api/productos` (se filtra en el cliente, volumen de productos es bajo).
