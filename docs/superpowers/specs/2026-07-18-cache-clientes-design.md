# Caché en servidor para lecturas de `ClienteService`

**Fecha:** 2026-07-18
**Motivación:** la pantalla Clientes de la app Flutter dispara una consulta a la base de datos cada vez que se abre (`initState`). El origen del backend es `ClienteController` → `ClienteService` → `ClienteRepository`. Esta primera optimización ataca el lado servidor: reducir las consultas repetidas a la base de datos para vendedores/rutas que se piden con frecuencia. La optimización del lado cliente (Flutter) queda fuera de este spec y se abordará por separado.

## Contexto de despliegue

La aplicación corre en una sola instancia (sin balanceador de carga ni múltiples nodos), lo que permite usar un caché en memoria del propio proceso sin necesidad de infraestructura distribuida (Redis, etc.).

## Alcance

Se cachean las 4 operaciones de lectura de `ClienteService`:

- `getClientesByVendedor(codigoVendedor)`
- `getClientesByRuta(ruta)`
- `getClienteById(rut, codigo)`
- `getAllClientes()`

## Arquitectura

Spring Cache (abstracción `@Cacheable`/`@CacheEvict`) con **Caffeine** como proveedor, en memoria, sin TTL (la invalidación es 100% por escritura — ver más abajo). Se agrega:

- Dependencias en `pom.xml`: `spring-boot-starter-cache` y `caffeine`.
- `@EnableCaching` en `DipalzaApplication` (o una clase `@Configuration` dedicada, ej. `CacheConfig`).
- Un `CacheManager` (`CaffeineCacheManager`) con 4 regiones, cada una con `maximumSize=500` como red de seguridad ante crecimiento anómalo de keys — no como mecanismo de expiración funcional.

## Regiones de caché y keys

| Región | Método | Key |
|---|---|---|
| `clientesByVendedor` | `getClientesByVendedor` | `codigoVendedor` |
| `clientesByRuta` | `getClientesByRuta` | `ruta` |
| `clientesById` | `getClienteById` | `rut + codigo` (SpEL compuesto) |
| `allClientes` | `getAllClientes` | key fija (sin parámetros) |

## Invalidación

`createOrUpdateCliente` y `deleteCliente` se anotan con `@CacheEvict` sobre las 4 regiones con `allEntries = true`.

**Razón:** una actualización puede cambiar el `codigoVendedor` o `codigoRuta` de un cliente, y en ese caso la key *anterior* (la que quedaría stale) no está disponible sin una lectura previa del estado actual. Dado que las escrituras sobre clientes son mucho menos frecuentes que las lecturas (la app las consulta constantemente; un cliente se edita rara vez), vaciar las 4 regiones completas en cada escritura es la opción más simple y correcta, con costo despreciable frente al beneficio en lecturas.

No hay expiración por tiempo (TTL): la única vía de invalidación es el evict en escritura.

## Manejo de errores

Sin cambios de comportamiento: Spring Cache no cachea resultados de invocaciones que lanzan excepción, así que un fallo transitorio de la base de datos no queda cacheado.

## Testing

Se extiende `ClienteServiceTest` (ya existente) con:

1. Un test que llama dos veces a `getClientesByVendedor` con el mismo código y verifica (vía Mockito `verify(times(1))`) que `clienteRepository.findByCodigoVendedorOrderByRazonAsc` solo se invoca una vez — confirma que la segunda lectura viene del caché.
2. Un test equivalente para al menos una de las otras 3 operaciones cacheadas (se elige `getClienteById` por ser la de key compuesta, más propensa a errores de configuración de SpEL).
3. Un test que, tras `createOrUpdateCliente`, verifica que una llamada posterior a `getClientesByVendedor` vuelve a golpear el repositorio (el evict funciona).

Estos tests requieren que el contexto de Spring tenga el `CacheManager` real activo (no un mock), por lo que se ejecutan como test de integración con `@SpringBootTest` (o cargando explícitamente la configuración de caché), no como test unitario puro con `@Mock`.

## Fuera de alcance

- Caché del lado cliente (Flutter) — se evaluará en una iteración separada.
- TTL / expiración por tiempo.
- Caché distribuido (Redis) — no se justifica con una sola instancia.
- Invalidación selectiva por key individual (se prefiere `allEntries = true` por simplicidad, ver sección Invalidación).
