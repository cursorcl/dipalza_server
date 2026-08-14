# Gestión de usuarios (web_client)

**Fecha:** 2026-08-12
**Repos afectados:** `dipalza_server`, `dipalza_web_client`

## Contexto y problema

Hoy no existe ninguna pantalla ni endpoint para administrar los usuarios
de `app_user` (crear, ver, modificar, habilitar/deshabilitar,
bloquear/desbloquear). La entidad `AppUser` ya tiene todo lo necesario
(`username`, `password`, `email`, `enabled`, `locked`, asociación
opcional a `Vendedor`), pero se creó y se sigue tocando a mano en la BD.
Se agrega un nuevo menú "Gestionar Usuarios" en `web_client`, restringido
a administradores.

**Hallazgos relevantes descubiertos durante el brainstorming** (no son
suposiciones, se verificaron contra el código y la BD real):

- `SecurityConfigProdSec` **no tiene** `@EnableMethodSecurity` (solo
  `SecurityConfigDevSec` lo tiene) — `@PreAuthorize` no se aplicaría en
  producción. La restricción por rol se implementa como regla de URL en
  el `SecurityFilterChain`, igual que el resto de las reglas existentes.
- `JwtAuthFilter` ya reconstruye los `GrantedAuthority` desde
  `user.getRoles()` leyendo la BD en cada request (no desde el claim del
  JWT), así que no hace falta tocar el login/JWT para que la
  autorización por rol funcione — ya está mal aprovechado, no roto.
- En la BD real de producción ya existe un usuario con `ROLE_ADMIN`
  (`lzamora`, que también tiene `ROLE_VENDEDOR`), así que activar la
  restricción no deja a nadie afuera.
- `AppRole` ya tiene los nombres `ROLE_ADMIN` y `ROLE_VENDEDOR`
  sembrados.
- `spring.mail.host` está siempre configurado (`smtp.gmail.com`
  hardcodeado); lo que puede faltar son `MAIL_USERNAME`/`MAIL_PASSWORD`
  (vía variables de entorno, sin default). "Servidor de correo no
  definido" en la práctica es una falla de autenticación/envío en tiempo
  de ejecución, no un bean ausente — se maneja con try/catch alrededor
  del envío, igual que ya hace el flujo de recuperación de clave.

## Alcance

Confirmado con el usuario durante el brainstorming:

- La app **móvil no se toca** — sigue sin restricción por rol (nunca
  llama estos endpoints nuevos).
- **Fuera de alcance:** asignación de roles desde la UI. Todo usuario
  creado por esta pantalla queda como "usuario normal" (sin
  `ROLE_ADMIN`); la edición tampoco expone un selector de roles. Promover
  a alguien a admin sigue siendo un proceso manual fuera de esta feature.
- **Fuera de alcance:** bloqueo automático tras N intentos fallidos de
  login. `locked` es un flag 100% manual desde esta pantalla — el
  bloqueo automático es trabajo futuro separado.
- **Fuera de alcance:** cambiar/resetear la clave de un usuario existente
  desde el diálogo de modificar (no se pidió; ya existe
  recuperación de clave por correo para el propio usuario).
- El campo "nombre" mencionado inicialmente por el usuario **no se
  agrega** a `AppUser` — fue una corrección del propio usuario durante el
  brainstorming. Cuando el usuario tiene un vendedor asociado, se
  **muestra** `Vendedor.nombre` (solo lectura), nunca se edita ahí.
- `username` es **fijo tras la creación** — no se puede modificar.

## Backend (`dipalza_server`)

### Seguridad

En `SecurityConfigProdSec.java` y `SecurityConfigDevSec.java`, agregar
**antes** de la regla `.requestMatchers("/api/**").authenticated()`:

```java
.requestMatchers("/api/usuarios/**").hasRole("ADMIN")
```

Spring Security evalúa las reglas en orden y usa la primera que matchea,
así que esta regla más específica debe ir antes de la genérica
`/api/**`. No se toca `@EnableMethodSecurity` ni el JWT/login — la
autorización ya funciona end-to-end vía `JwtAuthFilter` (ver hallazgos
arriba).

### Entidad `AppUser`

Sin cambios de columnas. Si la pantalla "ver" debe mostrar fecha de
creación, se agregan los mapeos (ya existen en la BD, faltan en Java):

```java
@Column(name = "created_at", insertable = false, updatable = false)
private LocalDate createdAt;
```

(`updated_at` se actualiza por la BD; no hace falta mapearlo si no se
va a mostrar).

### Nuevo `UsuarioAdminController` (`/api/usuarios`)

Nombre distinto al `UsuarioController` existente (`/api/usuario`,
singular, solo `cambiar-clave`) para no chocar rutas.

| Método | Ruta | Body / Respuesta |
|---|---|---|
| `GET` | `/api/usuarios` | → `List<UsuarioDTO>` |
| `GET` | `/api/usuarios/{id}` | → `UsuarioDTO` |
| `POST` | `/api/usuarios` | body `CrearUsuarioDTO` → `CrearUsuarioResultDTO` |
| `PUT` | `/api/usuarios/{id}` | body `ActualizarUsuarioDTO` → `UsuarioDTO`. `username` no se acepta en el body. |
| `PATCH` | `/api/usuarios/{id}/habilitar` | sin body → 200 |
| `PATCH` | `/api/usuarios/{id}/deshabilitar` | sin body → 200 |
| `PATCH` | `/api/usuarios/{id}/bloquear` | sin body → 200 |
| `PATCH` | `/api/usuarios/{id}/desbloquear` | sin body → 200 |

Un único DTO de lectura `UsuarioDTO` para lista y detalle (evita
duplicar forma, la lista simplemente no necesita usar todos los campos):

```java
public record UsuarioDTO(
    Long id,
    String username,
    String email,
    String codigoVendedor,   // null si no tiene vendedor asociado
    String tipoVendedor,     // null si no tiene vendedor asociado
    String nombreVendedor,   // null si no tiene vendedor asociado; viene de Vendedor.nombre
    boolean enabled,
    boolean locked,
    LocalDate createdAt      // null si no se mapea created_at
) {}

public record CrearUsuarioDTO(
    String username,
    String email,            // nullable
    String codigoVendedor,   // nullable
    String tipoVendedor,     // nullable
    String password
) {}

public record CrearUsuarioResultDTO(
    UsuarioDTO usuario,
    boolean correoEnviado
) {}

public record ActualizarUsuarioDTO(
    String email,            // nullable
    String codigoVendedor,   // nullable
    String tipoVendedor,     // nullable
    boolean enabled,
    boolean locked
) {}
```

Estos nombres de campo son los que debe usar el frontend en
`Usuario`/`CrearUsuarioPayload`/`ActualizarUsuarioPayload` (ver sección
Frontend) — record de Java serializa por el nombre del componente, sin
prefijo `get`, igual que `ProductoElegibleNumeradoDTO` en la feature de
numerados.

### Validaciones (en `UsuarioAdminService`, nuevo)

- `username`: obligatorio, único (usa `UserRepo.findByUsername` para
  chequear antes de guardar; el `UNIQUE` de BD es el resguardo final →
  400 con mensaje claro si choca).
- `email`: opcional; si viene, único (usa `UserRepo.findByEmail`; la BD
  ya tiene el índice único filtrado que permite múltiples NULL).
- `password` (solo en creación): obligatoria, mínimo 8 caracteres (igual
  regla que `UsuarioController.cambiarClave` y
  `AuthController.resetPassword`), se encripta con el `PasswordEncoder`
  ya inyectado en los controllers existentes.
- `codigoVendedor`/`tipoVendedor`: opcionales; si vienen, deben
  corresponder a un `Vendedor` existente (`VendedorRepository`,
  clave compuesta `codigo`+`tipo`) → 400 si no existe.
- `enabled`/`locked`: en creación siempre `true`/`false` respectivamente,
  no configurables desde el DTO de alta. En modificación, se aceptan
  ambos como booleanos explícitos.

### Correo de credenciales al crear

Nuevo método en `EmailService`:

```java
public void enviarCredencialesIniciales(String destinatario, String username, String claveInicial) {
    var mensaje = new SimpleMailMessage();
    mensaje.setTo(destinatario);
    mensaje.setSubject("Dipalza - Tu cuenta fue creada");
    mensaje.setText("""
            Se creó una cuenta de Dipalza para ti.

            Usuario: %s
            Clave inicial: %s

            Te recomendamos cambiar esta clave la primera vez que inicies sesión.
            """.formatted(username, claveInicial));
    mailSender.send(mensaje);
}
```

En `UsuarioAdminService.crear(...)`: si `email` no es nulo, intenta
enviar; captura cualquier excepción del envío (no detiene la creación
del usuario), y el resultado (`true`/`false`) se devuelve en la
respuesta del `POST` como `correoEnviado`, para que el frontend le
avise al admin si tiene que entregar la clave manualmente. Si no hay
`email`, no se intenta enviar nada (`correoEnviado: false` sin haberlo
intentado).

### Testing

- `UsuarioAdminServiceTest` (Mockito): creación válida, username
  duplicado (400), email duplicado (400), password corta (400), vendedor
  inexistente (400), creación sin email no intenta enviar correo,
  creación con email y fallo de envío no revierte la creación (usuario
  queda creado, `correoEnviado=false`), habilitar/deshabilitar/
  bloquear/desbloquear (idempotentes o no según corresponda),
  modificación no permite cambiar `username`.
- `UsuarioAdminControllerTest` (MockMvc): 200/400/404 de cada endpoint.
- Verificar manualmente (o con un test de configuración) que
  `/api/usuarios/**` efectivamente requiere `ROLE_ADMIN` y no cualquier
  autenticado — dado que es la primera vez que se usa este patrón en el
  proyecto, vale la pena un test de integración liviano contra el
  `SecurityFilterChain` real si el proyecto ya tiene ese tipo de test en
  algún lado (revisar durante la implementación; si no existe el
  patrón, un test manual documentado en el plan es aceptable).

## Frontend (`dipalza_web_client`)

### `AuthService`

Nuevo método:

```ts
isAdmin(): boolean {
  const token = this.getToken();
  if (!token) { return false; }
  const payload = JSON.parse(atob(token.split('.')[1]));
  const roles: string[] = payload.roles ?? [];
  return roles.includes('ROLE_ADMIN');
}
```

Decodificación manual del payload (sin agregar dependencia `jwt-decode`
nueva) — el claim `roles` ya lo emite `JwtService.generateAccess` en el
backend, solo que hoy nadie lo lee en el frontend.

### `AdminGuard`

Nuevo, en `src/app/core/guard/admin.guard.ts`, mismo patrón que
`AuthGuard` existente:

```ts
@Injectable({ providedIn: 'root' })
export class AdminGuard {
  constructor(private authService: AuthService, private router: Router) {}
  canActivate(): boolean {
    if (this.authService.isAdmin()) { return true; }
    this.router.navigate(['/']);
    return false;
  }
}
```

### Rutas y menú

- Nueva carpeta `src/app/usuarios/`, `usuarios.routes.ts` exporta
  `USUARIOS_ROUTES` (patrón idéntico a `numerados.routes.ts`).
- Registrada en `app.routes.ts` como hijo de `MainLayoutComponent`, con
  `canActivate: [AdminGuard]` además del `AuthGuard` que ya envuelve el
  layout completo.
- En `assets/data/routes.json`, nueva entrada:
  ```json
  {
    "path": "usuarios",
    "title": "Gestionar Usuarios",
    "iconType": "feather",
    "icon": "users",
    "class": "",
    "groupTitle": false,
    "badge": "",
    "badgeClass": "",
    "submenu": []
  }
  ```
- En `SidebarService`/`sidebar.component.ts`, filtrar esa entrada del
  arreglo cargado si `!authService.isAdmin()` — un usuario no-admin no
  ve el ítem en el menú.

### Modelo y servicio

`src/app/usuarios/models/model.ts`:

```ts
export interface Usuario {
  id: number;
  username: string;
  email: string | null;
  codigoVendedor: string | null;
  tipoVendedor: string | null;
  nombreVendedor: string | null; // solo lectura, viene del backend
  enabled: boolean;
  locked: boolean;
  createdAt?: string;
}

export interface CrearUsuarioPayload {
  username: string;
  email?: string;
  codigoVendedor?: string;
  tipoVendedor?: string;
  password: string;
}

export interface CrearUsuarioResult {
  usuario: Usuario;
  correoEnviado: boolean;
}

export interface ActualizarUsuarioPayload {
  email?: string;
  codigoVendedor?: string;
  tipoVendedor?: string;
  enabled: boolean;
  locked: boolean;
}
```

`UsuariosService` (`src/app/usuarios/usuarios.service.ts`): wrappers HTTP
delgados sobre los 7 endpoints, mismo patrón que `VentasService`.

### Pantallas

**`listado-usuarios`** (`src/app/usuarios/listado-usuarios/`): breadcrumb
+ buscador + `ngx-datatable`, mismo esqueleto que `listado-numerados`.
Columnas: Username, Correo, Vendedor (`nombreVendedor` o "-"), Estado
(badge: Activo / Deshabilitado / Bloqueado — deshabilitado tiene
prioridad visual sobre bloqueado si ambos aplican, ya que son
independientes). Botón "Agregar" arriba. 4 íconos por fila: ver (ojo),
modificar (lápiz/edit), habilitar-deshabilitar (ícono según estado
actual), bloquear-desbloquear (ídem). Los dos últimos llaman
directamente el `PATCH` correspondiente tras confirmar con
`Swal.fire` (mismo patrón que `quitarProducto` en la feature de
numerados).

**`ver-usuario`** (diálogo, `NgbActiveModal`): username, correo, vendedor
asociado (nombre + código/tipo), estado, fecha de creación si está
mapeada. Sin acciones, solo lectura.

**`crear-usuario`** (diálogo): username, correo (opcional), vendedor
(opcional, `NgbTypeahead` buscando por código o nombre — mismo patrón
que el buscador de productos ya construido, pero contra `Vendedor`),
clave (input de texto + botón "Generar clave" que rellena con una clave
aleatoria segura generada en el cliente, visible para poder copiarla).
Sin toggles de enabled/locked. Al guardar, si la respuesta trae
`correoEnviado: false` y el usuario tenía `email`, se muestra un aviso
("no se pudo enviar el correo, entregue la clave manualmente").

**`modificar-usuario`** (diálogo): username de solo lectura, correo
editable, vendedor editable (reasignar o quitar la asociación), toggle
habilitar/deshabilitar, toggle bloquear/desbloquear. Sin campo de clave.

### Testing

- Specs de `AuthService.isAdmin()` (con/sin token, con/sin
  `ROLE_ADMIN` en el claim) y `AdminGuard`.
- Specs de cada diálogo, mismo nivel de rigor que
  `GestionProductosNumeradosComponent`: `HttpTestingController` para las
  llamadas HTTP, `Swal.fire` mockeado para las confirmaciones de
  habilitar/deshabilitar/bloquear/desbloquear, casos de error 400 del
  backend mostrando el mensaje correcto.

## Resumen de decisiones tomadas durante el brainstorming

- Los íconos de fila son ver, modificar, habilitar/deshabilitar y
  bloquear/desbloquear — 4 en total (no 3 ni un único ícono combinado).
- `locked` es manual por ahora; el bloqueo automático tras intentos
  fallidos queda fuera de alcance.
- No se agrega campo "nombre" a `AppUser`; se muestra el nombre del
  vendedor asociado, solo lectura.
- `username` fijo tras la creación.
- La clave inicial la define el admin (escrita o generada con un botón),
  no hay flujo de invitación por correo con enlace — si hay `email`
  configurado y el servidor de correo funciona, se envía un correo con
  usuario+clave.
- Restricción a `ROLE_ADMIN` solo para `web_client`; la app móvil no se
  toca. Implementada como regla de URL en el `SecurityFilterChain`
  (no `@PreAuthorize`, porque `SecurityConfigProdSec` no tiene
  `@EnableMethodSecurity`).
- La creación y edición no exponen asignación de roles — todo usuario
  nuevo es "normal", nunca admin, desde esta pantalla.
