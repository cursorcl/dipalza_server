# Diseño: Asociación Vendedor–Rutas

**Fecha:** 2026-07-16
**Proyectos afectados:** `dipalza.springboot/dipalza` (backend), `dipalza.springboot/base_de_datos` (scripts SQL), `flutterDipalza` (app móvil)
**Objetivo:** Permitir que un vendedor tenga asociadas múltiples rutas que cubre (relación muchos a muchos), y que esa selección se gestione desde la pantalla de Configuración de la app móvil mediante chips seleccionables.

---

## Alcance y decisiones previas

- Cardinalidad: **muchos a muchos**. Un vendedor cubre varias rutas; una ruta puede ser cubierta por varios vendedores.
- La tabla de asociación es un **vínculo simple** (sin estado activo/inactivo, sin fechas de auditoría adicionales).
- La opción "Ruta" (single-select) que hoy existe en la pantalla de Configuración del vendedor **se reemplaza** por "Rutas" (multi-select, chips), representando esta nueva asociación maestra.
- El login **deja de pedir una ruta**: se elimina el selector de ruta de `LoginPage`. Tras autenticar, la app usa las rutas ya configuradas para ese vendedor (`vendedor_ruta`); si no tiene ninguna, se le obliga a seleccionarlas antes de continuar (ver sección 4).
- **Hallazgo relevante:** `Venta.codigoRuta` es una FK `NOT NULL` obligatoria en el backend (`VentaService` valida `"Falta el código de ruta!"`). Hoy ese valor se toma de `prefs.ruta` (una "ruta activa" seteada una sola vez, en el login). Como cada `Cliente` ya tiene su propia ruta (`Cliente.codigoRuta` en el backend, campo `ruta` en `ClientesModel`), se decide **eliminar el concepto de "ruta activa del vendedor"** y derivar `codigoRuta` de la venta directamente desde el cliente seleccionado. Esto requiere corregir un bug preexistente: `ClientesModel.fromJson` lee `json["tuta"]` (typo) en lugar de `json["ruta"]`, por lo que ese campo llega siempre vacío hoy. Con el typo corregido, `_clienteSeleccionado.ruta` pasa a ser la fuente de `codigoRuta` en `venta.encabezado.edicion.page.dart`, y `prefs.ruta` deja de usarse en la creación de venta.
- **Fuera de alcance / sin cambios:** `ClientesProvider.obtenerListaClientes` y `clientes.page.dart` (ya traen todos los clientes del vendedor sin filtrar por ruta; el parámetro de ruta que reciben ya era ignorado por el backend, se deja como está). `PreferenciasUsuario.ruta` (getter/setter) no se elimina — queda simplemente sin escritores activos, para no romper el método ya-no-llamado `ClientesProvider.obtenerListaClientesv2`.

---

## 1. Base de datos (`dipalza.springboot/base_de_datos`)

### Tabla nueva: `vendedor_ruta`

`vendedor` tiene PK compuesta (`codigo`, `tipo`), por lo que la tabla de asociación referencia ambas columnas. Sigue el patrón de nombres ya usado en `app_user_roles` (`PK_<tabla>`, `FK_<tabla>_<referenciada>`).

```sql
CREATE TABLE dbo.vendedor_ruta (
    codigo_vendedor varchar(3)  COLLATE Modern_Spanish_CI_AS NOT NULL,
    tipo_vendedor   varchar(1)  COLLATE Modern_Spanish_CI_AS NOT NULL,
    codigo_ruta     varchar(10) COLLATE Modern_Spanish_CI_AS NOT NULL,
    CONSTRAINT PK_vendedor_ruta PRIMARY KEY (codigo_vendedor, tipo_vendedor, codigo_ruta),
    CONSTRAINT FK_vendedor_ruta_vendedor FOREIGN KEY (codigo_vendedor, tipo_vendedor) REFERENCES dbo.vendedor(codigo, tipo),
    CONSTRAINT FK_vendedor_ruta_ruta FOREIGN KEY (codigo_ruta) REFERENCES dbo.ruta(codigo)
);
```

### Dónde se agrega

1. `db/install_dipalza_sync.sql` — DDL canónico. Se inserta justo después de las definiciones de `vendedor` y `ruta` (líneas ~157-174).
2. `archive/migration/migration_20260716.sql` — script incremental nuevo, mismo estilo que `migration_20260529.sql`:

```sql
-- Agrega tabla de asociación vendedor-rutas (rutas que cubre cada vendedor)
CREATE TABLE dbo.vendedor_ruta (
    codigo_vendedor varchar(3)  COLLATE Modern_Spanish_CI_AS NOT NULL,
    tipo_vendedor   varchar(1)  COLLATE Modern_Spanish_CI_AS NOT NULL,
    codigo_ruta     varchar(10) COLLATE Modern_Spanish_CI_AS NOT NULL,
    CONSTRAINT PK_vendedor_ruta PRIMARY KEY (codigo_vendedor, tipo_vendedor, codigo_ruta),
    CONSTRAINT FK_vendedor_ruta_vendedor FOREIGN KEY (codigo_vendedor, tipo_vendedor) REFERENCES dbo.vendedor(codigo, tipo),
    CONSTRAINT FK_vendedor_ruta_ruta FOREIGN KEY (codigo_ruta) REFERENCES dbo.ruta(codigo)
);
```

---

## 2. Backend Spring Boot (`dipalza.springboot/dipalza`, paquete `cl.eos.dipalza`)

### Clave compuesta: `entity/ids/VendedorRutaId.java`

`@Embeddable implements Serializable`, mismo estilo que `VendedorId`:

```java
@Embeddable
public class VendedorRutaId implements Serializable {
    @Column(name = "codigo_vendedor", length = 3, nullable = false)
    private String codigoVendedor;
    @Column(name = "tipo_vendedor", length = 1, nullable = false)
    private String tipoVendedor;
    @Column(name = "codigo_ruta", length = 10, nullable = false)
    private String codigoRuta;
    // getters/setters, equals, hashCode
}
```

### Entidad: `entity/VendedorRuta.java`

```java
@Entity
@Table(name = "vendedor_ruta", schema = "dbo")
public class VendedorRuta {
    @EmbeddedId
    private VendedorRutaId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
        @JoinColumn(name = "codigo_vendedor", referencedColumnName = "codigo", insertable = false, updatable = false),
        @JoinColumn(name = "tipo_vendedor", referencedColumnName = "tipo", insertable = false, updatable = false)
    })
    private Vendedor vendedor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "codigo_ruta", referencedColumnName = "codigo", insertable = false, updatable = false)
    private Ruta ruta;
    // getters/setters
}
```

### Repositorio: `repository/VendedorRutaRepository.java`

```java
@Repository
public interface VendedorRutaRepository extends JpaRepository<VendedorRuta, VendedorRutaId> {
    List<VendedorRuta> findByIdCodigoVendedorAndIdTipoVendedor(String codigoVendedor, String tipoVendedor);
    void deleteByIdCodigoVendedorAndIdTipoVendedor(String codigoVendedor, String tipoVendedor);
}
```

### Servicio: `service/VendedorRutaService.java`

Constructor injection (`VendedorRutaRepository`, `VendedorRepository`, `RutaRepository`, `RutaMapper`).

- `List<RutaDTO> getRutasByVendedor(String codigo, String tipo)`: busca asociaciones existentes y mapea la `Ruta` de cada una con `RutaMapper`.
- `List<RutaDTO> asignarRutas(String codigo, String tipo, List<String> codigosRuta)`, `@Transactional`:
  1. Verifica que el vendedor exista (`VendedorRepository.findById`); si no, `ResponseStatusException(HttpStatus.NOT_FOUND, "Vendedor no encontrado")`.
  2. Verifica que cada código de ruta exista (`RutaRepository.findById`); si alguno no existe, `ResponseStatusException(HttpStatus.NOT_FOUND, "Ruta <codigo> no encontrada")`.
  3. **Reemplaza el set completo**: `deleteByIdCodigoVendedorAndIdTipoVendedor(...)` seguido de `saveAll(...)` con las nuevas asociaciones. Se elige reemplazo total (no altas/bajas incrementales) porque la UI de Flutter envía la selección completa de chips en cada guardado.
  4. Retorna `getRutasByVendedor(codigo, tipo)`.

Sin manejador de excepciones global nuevo — se sigue el patrón existente de `AuthController` (lanzar `ResponseStatusException` directamente desde el controller/servicio).

### Controlador: `controller/VendedorRutaController.java`

No existe hoy un `VendedorController`; se crea este controlador dedicado a la asociación:

```java
@RestController
@RequestMapping("/api/vendedores/{codigo}/{tipo}/rutas")
public class VendedorRutaController {
    // constructor injection de VendedorRutaService

    @GetMapping
    public List<RutaDTO> getRutas(@PathVariable String codigo, @PathVariable String tipo) { ... }

    @PutMapping
    public List<RutaDTO> setRutas(@PathVariable String codigo, @PathVariable String tipo,
                                   @RequestBody List<String> codigosRuta) { ... }
}
```

Delegación directa al servicio (controller delgado, igual que `RutaController`).

---

## 3. Flutter (`flutterDipalza`)

El nuevo flujo multi-select (Configuración y login) reemplaza toda dependencia de `prefs.ruta` como "ruta activa del vendedor" — ver hallazgo en la sección de Alcance. `prefs.ruta` deja de tener escritores, aunque el getter/setter no se elimina (evita romper el método ya no invocado `ClientesProvider.obtenerListaClientesv2`).

### `RutasPage` (`lib/src/page/rutas/rutas.page.dart`)

Se agregan tres parámetros opcionales, retrocompatibles con el uso actual (sin argumentos) desde `LoginPage`:

```dart
class RutasPage extends StatefulWidget {
  final bool multiSelect;
  final List<RutasModel> seleccionInicial;
  final bool obligatorio;
  const RutasPage({
    Key? key,
    this.multiSelect = false,
    this.seleccionInicial = const [],
    this.obligatorio = false,
  }) : super(key: key);
}
```

- `multiSelect == false` (default): comportamiento actual sin cambios (tap en fila → `AppNavigator.pop(ruta)` con un solo `RutasModel`).
- `multiSelect == true`: cada fila muestra un `Checkbox` (estado en `Set<String> _codigosSeleccionados`, inicializado desde `seleccionInicial`); el `AppBar` agrega una acción "Guardar" (ícono check) que hace `AppNavigator.pop(...)` con la `List<RutasModel>` filtrada por códigos seleccionados.
- `obligatorio == true` (solo tiene efecto junto con `multiSelect: true`, usado por el flujo de login forzado — ver sección 4): `PopScope(canPop: false, ...)` bloquea el back del sistema/gesto y no se muestra flecha de "volver"; el botón "Guardar" queda deshabilitado hasta que haya al menos 1 ruta marcada.

### `app_router.dart`

El case `AppRoutes.rutas` lee `settings.arguments` como `Map<String, dynamic>?` (mismo patrón usado en `ventaDetalle`/`ventaItemEdicion`) para extraer `multiSelect`, `seleccionInicial` y `obligatorio`; si no hay argumentos, construye `RutasPage()` como hoy.

### Nuevo `VendedorRutaProvider` (`lib/src/provider/vendedor_ruta_provider.dart`)

```dart
class VendedorRutaProvider {
  final _dio = ApiClient().dio;

  Future<List<RutasModel>> obtenerRutasAsignadas(String codigo, String tipo) async {
    final res = await _dio.get('/api/vendedores/$codigo/$tipo/rutas');
    return (res.data as List).map((j) => RutasModel.fromJson(j)).toList();
  }

  Future<List<RutasModel>> guardarRutasAsignadas(String codigo, String tipo, List<String> codigosRuta) async {
    final res = await _dio.put('/api/vendedores/$codigo/$tipo/rutas', data: codigosRuta);
    return (res.data as List).map((j) => RutasModel.fromJson(j)).toList();
  }
}
```

Manejo de errores por try/catch con `print` + retorno de lista vacía, igual que `ClientesProvider`.

### `PreferenciasUsuario` (`lib/src/share/prefs_usuario.dart`)

Nuevo par getter/setter para cachear localmente los códigos de las rutas asignadas (evita depender solo de la llamada de red al reabrir la pantalla):

```dart
List<String> get rutasAsignadas => _prefs.getStringList('rutasAsignadas') ?? [];
set rutasAsignadas(List<String> value) => _prefs.setStringList('rutasAsignadas', value);
```

### `ConfiguracionPage` (`lib/src/page/config/preferences.page.dart`)

- Reemplaza `RutasModel? _rutaSeleccionada` por `List<RutasModel> _rutasAsignadas = []`.
- `initState`: dispara `VendedorRutaProvider().obtenerRutasAsignadas(_prefs.vendedor, _prefs.tipo)` para hidratar `_rutasAsignadas`.
- Reemplaza el método `_pickRuta` por `_pickRutas`:

```dart
Future<void> _pickRutas() async {
  final seleccion = await AppNavigator.pushNamed(
    AppRoutes.rutas,
    arguments: {'multiSelect': true, 'seleccionInicial': _rutasAsignadas},
  );
  if (seleccion != null) {
    final nuevas = List<RutasModel>.from(seleccion);
    setState(() => _rutasAsignadas = nuevas);
    _prefs.rutasAsignadas = nuevas.map((r) => r.codigo).toList();
    await VendedorRutaProvider()
        .guardarRutasAsignadas(_prefs.vendedor, _prefs.tipo, _prefs.rutasAsignadas);
  }
}
```

- Reemplaza la `ListTile` "Ruta" (líneas 511-524) por una sección "Rutas" cuyo subtítulo/cuerpo muestra las seleccionadas como chips (`Wrap` de `Chip`, igual look que la sección "Recientes" ya existente en la misma pantalla), y cuyo `onTap` (en la fila o en un ícono de edición) llama a `_pickRutas`.

### Corrección: `ClientesModel` (`lib/src/model/clientes_model.dart`)

Bug preexistente en `fromJson` (línea 41): `ruta: json["tuta"] ?? ""` → se corrige a `ruta: json["ruta"] ?? ""`. Sin este fix, el campo `ruta` del cliente siempre llega vacío y no sirve como fuente de `codigoRuta` para la venta.

### `venta.encabezado.edicion.page.dart` — origen de `codigoRuta`

En `saveVenta()` (línea 250), cambia:

```dart
codigoRuta: pref.ruta,
```

por:

```dart
codigoRuta: _clienteSeleccionado!.ruta,
```

Ya no depende de `prefs.ruta` ni de ningún concepto de "ruta activa del vendedor" — la ruta de la venta es la del cliente que se está facturando.

---

## 4. Login sin selección de ruta (`lib/src/page/login/`)

Al guardar (con `obligatorio: true`), `RutasPage` hace `AppNavigator.pop(...)` igual que en modo `multiSelect` normal — quien la invocó (`LoginPage`) es responsable de persistir la selección vía `VendedorRutaProvider` y navegar a Home.

### `LoginBloc` (`lib/src/bloc/login_bloc.dart`) y `Validators` (`lib/src/page/login/login_validacion.dart`)

Se elimina todo lo relacionado a ruta, ya que el login deja de exigirla para habilitar el botón "Ingresar":
- Se quita `_rutaController`, `rutaStream`, `changeRuta`, `ruta` (getter) de `LoginBloc`.
- Se quita `validarRuta` de `Validators`.
- `formValidStream` pasa de `Rx.combineLatest3(usuarioStream, passwordStream, rutaStream, ...)` a `Rx.combineLatest2(usuarioStream, passwordStream, (a, b) => true)`.

### `LoginPage` (`lib/src/page/login/login.page.dart`)

- Se elimina `_rutaSeleccionada`, el widget `_crearSelectorRutas` y su uso en `_loginForm`.
- Se elimina el reseteo `_rutaSeleccionada = null; bloc.changeRuta('');` dentro de `_crearBotonesSecundarios` (ya no aplica).
- Se elimina la línea `if (_rutaSeleccionada != null) prefs.ruta = ...` en `_login()`.
- En `_login()`, tras un login exitoso (`resp.status == 200`), antes de navegar a Home:

```dart
final rutas = await VendedorRutaProvider()
    .obtenerRutasAsignadas(response.codigo, response.tipo);

if (rutas.isEmpty) {
  final seleccion = await AppNavigator.pushNamed(
    AppRoutes.rutas,
    arguments: {'multiSelect': true, 'obligatorio': true},
  );
  final nuevas = List<RutasModel>.from(seleccion);
  await VendedorRutaProvider().guardarRutasAsignadas(
      response.codigo, response.tipo, nuevas.map((r) => r.codigo).toList());
}

AppNavigator.pushReplacementNamed(AppRoutes.home);
```

Si `obtenerRutasAsignadas` lanza una excepción (falla de red), se captura y se muestra el mismo diálogo de error que ya usa `_login()` para credenciales inválidas, sin navegar — el usuario puede reintentar tocando "Ingresar" de nuevo.

---

## Manejo de errores

- Backend: `ResponseStatusException` con `HttpStatus.NOT_FOUND` para vendedor o ruta inexistente al guardar asociaciones — sin handler global, siguiendo convención actual del proyecto.
- Flutter: los providers nuevos siguen el patrón try/catch + lista vacía de `ClientesProvider`/`VendedorProvider`; errores no bloquean la UI, se puede reintentar reabriendo el picker.

## Testing

- Backend: tests de servicio (`VendedorRutaServiceTest`, Mockito puro) cubriendo: vendedor no encontrado, ruta no encontrada, reemplazo correcto del set completo (delete + saveAll), listado vacío. Tests de controller (`@WebMvcTest`) verificando `GET`/`PUT` y códigos de estado, siguiendo la Capa 1/Capa 2 documentadas en `2026-07-12-tests-springboot-design.md`.
- Flutter: no hay suite de tests automatizados existente para providers/páginas de este estilo en el repo; verificación manual queda como paso de verificación funcional, cubriendo al menos:
  - Configuración → seleccionar rutas con chips → guardar → reabrir y confirmar persistencia.
  - Login de un vendedor sin rutas configuradas → pantalla bloqueante de selección (sin back) → guardar → llega a Home.
  - Login de un vendedor con rutas ya configuradas → va directo a Home, sin pantalla de rutas.
  - Crear una venta y confirmar que `codigoRuta` enviado corresponde a la ruta del cliente seleccionado (no a `prefs.ruta`).
