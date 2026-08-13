# Cambio de clave obligatorio (clave temporal)

**Fecha:** 2026-08-12
**Repos afectados:** `dipalza_server`, `dipalza_mobile`, `dipalza_web_client`
**Feature previa relacionada:** [gestión de usuarios](2026-08-12-gestion-usuarios-design.md) (creación de usuarios desde el panel web), cambio de clave autenticado y recuperación por correo (PR #24/#32-36 mencionados en CLAUDE.md)

## Contexto y problema

Hoy existen dos formas de que un usuario reciba una clave que él mismo no eligió:

1. Un admin crea su cuenta desde "Gestionar Usuarios" y le asigna una clave inicial (generada o escrita por el admin).
2. El usuario usa "olvidé mi clave" — flujo actual de 2 pasos: se le envía un código de 6 dígitos por correo, y en una sola pantalla escribe el código y elige su clave nueva, sin pasar por un login intermedio.

En ningún caso el sistema fuerza al usuario a confirmar/cambiar esa clave después. Se quiere agregar una marca en la cuenta que obligue a cambiar la clave la primera vez que inicia sesión con una clave que no fue elegida por el usuario mismo de forma autenticada.

Los usuarios creados desde "Gestionar Usuarios" suelen ser vendedores que usan la **app móvil** (`dipalza_mobile`), no el web client (que es solo para admins — `weblogin` ya rechaza con 401 cualquier cuenta que tenga un `Vendedor` asociado). Por eso el foco principal es mobile, pero el mecanismo de backend es común a ambas apps, y el flujo de "olvidé mi clave" en la app **web** también debe quedar unificado para que una cuenta admin que lo use pase por el mismo camino.

## Alcance

- Se agrega `AppUser.mustChangePassword` (boolean), marcado en creación (Gestión de usuarios) y en "olvidé mi clave" (ambas apps), limpiado automáticamente al completar un cambio de clave autenticado.
- El flujo de "olvidé mi clave" se rediseña por completo: en vez de código de 6 dígitos + clave elegida en la misma pantalla, el backend genera una **clave temporal**, la deja como clave activa de la cuenta, y la envía por correo. Se elimina el código de 6 dígitos, `PasswordResetToken`, su tabla, y el endpoint `/auth/reset-password`, en los 3 repos.
- Aplica a **todos los roles**, incluido admin — una cuenta admin que usa "olvidé mi clave" desde la web pasa por el mismo mecanismo.
- El correo con la clave temporal solo incluye el botón "Cambiar mi clave" (enlace al web client) si el destinatario tiene `ROLE_ADMIN` — para el resto (vendedores en mobile) es solo texto, sin enlace, porque el cambio se resuelve con el diálogo forzado dentro de la app, no haciendo clic en el correo.
- El diálogo/pantalla de cambio forzado es **bloqueante** (sin botón de cerrar, sin omitir) en ambas apps, pide solo la clave nueva + confirmación (la clave actual/temporal se reenvía automáticamente en segundo plano, ya que el usuario acaba de escribirla para iniciar sesión — no se le vuelve a pedir). Al completarse, se cierra la sesión y se exige iniciar sesión de nuevo con la clave recién elegida.

**Fuera de alcance:**
- Un admin no puede forzar manualmente `mustChangePassword=true` en un usuario existente desde "Modificar Usuario" — solo se marca automáticamente en los dos casos de arriba.
- No se agrega expiración a la marca `mustChangePassword` — queda `true` hasta que el usuario cambia su clave, sin límite de tiempo.

## Backend (`dipalza_server`)

### Modelo de datos

`AppUser` (entidad, `dipalza/src/main/java/cl/eos/dipalza/entity/AppUser.java`): nuevo campo

```java
@Column(name = "must_change_password")
private boolean mustChangePassword = false;
```

con getter/setter estándar (`isMustChangePassword()`/`setMustChangePassword(boolean)`).

Se elimina por completo:
- Entidad `PasswordResetToken` (`dipalza/src/main/java/cl/eos/dipalza/entity/PasswordResetToken.java`)
- `PasswordResetTokenRepo` (`dipalza/src/main/java/cl/eos/dipalza/repository/PasswordResetTokenRepo.java`)
- Tabla `dbo.app_password_reset_token`

### Migración de base de datos

Dos archivos, siguiendo el patrón ya usado en `migration_20260808.sql`:

1. `base_de_datos/archive/migration/migration_20260812.sql` (para aplicar a la BD real de producción **antes** de desplegar el jar nuevo, según la práctica ya establecida en este proyecto):

```sql
SET QUOTED_IDENTIFIER ON;
GO

BEGIN TRAN;

ALTER TABLE dbo.app_user
    ADD must_change_password bit NOT NULL DEFAULT 0;
GO

DROP TABLE dbo.app_password_reset_token;

COMMIT TRAN;
```

2. `base_de_datos/deploy_desde_cero/01_esquema_ventas.sql` — actualizar para que una instalación desde cero quede en el mismo estado final: agregar `must_change_password bit NOT NULL DEFAULT 0` a la definición de `dbo.app_user`, y quitar el `CREATE TABLE dbo.app_password_reset_token` (y su bloque de comentario asociado).

### `AuthController`

**DTOs de respuesta** (agregan `mustChangePassword` al final, sin tocar el orden de los campos existentes — Jackson serializa records por nombre de componente):

```java
public record TokenResponse(String accessToken, String refreshToken, long expiresInSeconds,
        VendedorDTO vendedor, boolean mustChangePassword) {}

public record WebLoginRes(String token, String refreshToken, long expiresInSeconds, long id,
        String username, String firstName, String lastName, boolean mustChangePassword) {}
```

`generateTokenRes(...)` (usado por `/auth/login` y `/auth/refresh`), `weblogin()` y `webRefresh()` pasan `u.isMustChangePassword()` al construir la respuesta.

**`POST /auth/forgot-password`** — reescrito. Mantiene exactamente el mismo contrato externo (`ForgotPasswordReq(usernameOrEmail)`, siempre responde 200, nunca filtra si la cuenta existe, mismo throttle de 60s por username) pero cambia qué hace internamente:

```java
@PostMapping("/forgot-password")
public void forgotPassword(@RequestBody ForgotPasswordReq req) {
    String clave = req.usernameOrEmail() == null ? "" : req.usernameOrEmail().trim();
    Optional<AppUser> userOpt = clave.contains("@") ? users.findByEmail(clave) : users.findByUsername(clave);

    if (userOpt.isEmpty() || userOpt.get().getEmail() == null)
        return;

    AppUser u = userOpt.get();

    Instant ultima = ultimaSolicitudPorUsername.get(u.getUsername());
    if (ultima != null && ultima.isAfter(Instant.now().minusSeconds(RESET_RATE_LIMIT_SEGUNDOS)))
        return;
    ultimaSolicitudPorUsername.put(u.getUsername(), Instant.now());

    String claveTemporal = generarClaveTemporal();
    u.setPassword(enc.encode(claveTemporal));
    u.setMustChangePassword(true);
    users.save(u);
    refreshTokenService.revocarTokensDeUsuario(u);

    boolean esAdmin = u.getRoles().stream().anyMatch(r -> "ROLE_ADMIN".equals(r.getName()));
    emailService.enviarClaveTemporalPorOlvido(u.getEmail(), u.getUsername(), claveTemporal, esAdmin);
}

private String generarClaveTemporal() {
    String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%";
    StringBuilder sb = new StringBuilder(12);
    for (int i = 0; i < 12; i++)
        sb.append(chars.charAt(secureRandom.nextInt(chars.length())));
    return sb.toString();
}
```

(Mismo alfabeto que ya usa `CrearUsuarioComponent.generarClaveAleatoria()` en el web client — sin caracteres ambiguos I/O/l/0/1.)

Se elimina: endpoint `POST /auth/reset-password`, record `ResetPasswordReq`, el campo `resetTokenRepo` y su parámetro de constructor, la constante `RESET_TOKEN_MINUTOS`, y los imports de `PasswordResetToken`/`PasswordResetTokenRepo`. El método `hashToken(...)` **se mantiene** — lo sigue usando `generateTokenRes(...)` para hashear el refresh token, no tiene relación con el código de recuperación eliminado.

### `UsuarioAdminService.crear()`

Se agrega una línea junto a `u.setEnabled(true); u.setLocked(false);`:

```java
u.setMustChangePassword(true);
```

Y el llamado a `emailService.enviarCredencialesIniciales(...)` pasa a incluir el flag de admin (siempre `false` en la práctica, ya que este flujo nunca crea administradores, pero se calcula igual por consistencia — ver DTO abajo).

### `UsuarioController.cambiarClave()`

Una línea antes de `users.save(u)`:

```java
u.setMustChangePassword(false);
```

### `EmailService`

- Se elimina `enviarCodigoRecuperacionClave`.
- `enviarCredencialesIniciales` agrega un parámetro `boolean esAdmin`.
- Nuevo método `enviarClaveTemporalPorOlvido(String destinatario, String username, String claveTemporal, boolean esAdmin)`, mismo mecanismo (HTML compartido, `MimeMessageHelper`) pero con asunto "Dipalza - Tu clave fue restablecida" y cuerpo indicando que fue a pedido del propio usuario.
- En ambos métodos, el botón "Cambiar mi clave" solo se agrega si `esAdmin == true`; si no, se envía sin botón (mismo helper `construirHtml` que ya soporta `textoBoton == null`).

## Frontend móvil (`dipalza_mobile`)

### `LoginResponseModel` (`lib/src/model/login_response_model.dart`)

Agrega `final bool mustChangePassword;`, parseado desde el JSON de `/auth/login`.

### `olvide_clave.page.dart` + `recuperar_clave_provider.dart`

Se simplifican a un solo paso: campo "Usuario o correo" + botón "Enviar" → mensaje de éxito ("Revisa tu correo"), igual que el `ForgotComponent` del web client. Se eliminan los campos de código de 6 dígitos y clave nueva/confirmar, el método `restablecerClave` del provider (`POST /auth/reset-password` ya no existe), y las validaciones de `ResetClaveBloc` asociadas a esos campos.

### Diálogo/pantalla de cambio forzado

Se adapta `cambiar_clave.page.dart` para un modo forzado: recibe la clave con la que el usuario acaba de iniciar sesión (`bloc.password`/`prefs.password`, ya se guarda hoy en `PreferenciasUsuario`), oculta el campo "clave actual" del formulario, y la usa automáticamente como `claveActual` al llamar `PUT /api/usuario/cambiar-clave`. La pantalla no permite volver atrás (bloquea el botón físico/gesto de retroceso) mientras esté en modo forzado.

### `login.page.dart`

Justo después de parsear `LoginResponseModel`, antes de la lógica actual de selección de ruta/navegación a home: si `response.mustChangePassword == true`, navega a la pantalla de cambio forzado (pasando la clave recién escrita) en vez de continuar el flujo normal. Al completar el cambio ahí: se limpian las credenciales guardadas (`prefs.borrarCredenciales()`) y se navega de vuelta al login, exigiendo iniciar sesión de nuevo con la clave nueva.

## Frontend web (`dipalza_web_client`)

### `User` (modelo, `src/app/core/models/user.ts`)

Agrega `mustChangePassword: boolean;`.

### `AuthService`

Se elimina `resetPassword()` y `resetPasswordUrl`. El resto de métodos (`login`, `changePassword`, `forgotPassword`) no cambian de firma.

### `forgot.component.ts` / `.html`

Se elimina `irARestablecer()` y el botón/enlace correspondiente en el HTML — ya no hay una pantalla de "restablecer con código" a la que ir. El resto del componente (pedir usuario/correo, mostrar "revisa tu correo") queda igual, ya tiene la forma correcta.

### `reset.component.ts` / `.html` / `.sass`

Se elimina el componente completo (carpeta `src/app/authentication/reset/`), y su ruta en `auth.routes.ts` (`path: 'reset'`).

### Diálogo de cambio forzado

Nuevo componente pequeño (p.ej. `CambiarClaveObligatorioComponent`), variante de `CambiarClaveComponent` sin el campo "clave actual": recibe la clave recién escrita en el login como `@Input()`, pide solo clave nueva + confirmación, y llama a `authService.changePassword(claveActualRecibida, claveNueva)`.

### `signin.component.ts`

En el `next` del `onSubmit()`, después de confirmar que hay `token`: si `this.authService.currentUserValue.mustChangePassword`, en vez de `this.router.navigate(['/'])` abre el diálogo nuevo vía `NgbModal` con `{ backdrop: 'static', keyboard: false }` (sin botón de cerrar en el template) pasándole la clave recién escrita. Al cerrar el modal (cambio completado): `authService.logout()` + `router.navigate(['/authentication/signin'])`.

## Testing

- Backend: `AuthControllerTest`/`PasswordFlowControllerTest` — casos para `forgot-password` (genera clave temporal, marca `mustChangePassword`, revoca tokens, envía correo con/sin botón según rol), remover los casos del código de 6 dígitos eliminado. `UsuarioAdminServiceTest` — nuevo caso: `crear()` deja `mustChangePassword=true`. `UsuarioController`/test de `cambiar-clave` — nuevo caso: limpia el flag. `EmailServiceTest` — casos para `enviarClaveTemporalPorOlvido` con/sin botón.
- Mobile: tests existentes de `olvide_clave` se simplifican al nuevo flujo de un paso; nuevo test para la pantalla de cambio forzado (oculta clave actual, usa la del login).
- Web: `signin.component.spec.ts` — nuevo caso para el modal bloqueante cuando `mustChangePassword=true`; specs de `forgot`/`reset` se ajustan (se elimina el spec de `reset.component`).

## Resumen de decisiones tomadas durante el brainstorming

- El flujo de "olvidé mi clave" se unifica por completo con el de creación de cuenta: clave temporal generada por el backend y enviada por correo, sin código de 6 dígitos ni pantalla para elegir clave en ese momento.
- Se elimina toda la infraestructura del código de 6 dígitos (`PasswordResetToken`, tabla, endpoint) en vez de dejarla sin usar.
- Aplica a todos los roles, incluido admin — una cuenta admin que resetea su clave desde la web pasa por el mismo mecanismo y también queda con `mustChangePassword=true`.
- El correo solo incluye el enlace/botón de cambio de clave si el destinatario es admin (porque solo admins usan el web client); para el resto, el cambio se resuelve con el diálogo forzado dentro de la app tras iniciar sesión.
- El diálogo forzado es bloqueante (sin cerrar/omitir) en ambas apps, pide solo la clave nueva (la clave actual/temporal se reenvía automáticamente, sin pedírsela de nuevo al usuario), y al completarse cierra la sesión y exige loguearse de nuevo.
