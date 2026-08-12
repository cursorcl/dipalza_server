# Gestión de usuarios (web_client) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Nueva pantalla "Gestionar Usuarios" en `web_client`, restringida a `ROLE_ADMIN`, para crear/ver/modificar/habilitar-deshabilitar/bloquear-desbloquear usuarios de `app_user`, con asociación opcional a un `Vendedor`.

**Architecture:** Backend: nuevo `UsuarioAdminController`/`UsuarioAdminService` (`/api/usuarios`) restringido por regla de URL (`hasRole("ADMIN")`) en el `SecurityFilterChain` de ambos perfiles seguros — no `@PreAuthorize`, porque `SecurityConfigProdSec` no tiene `@EnableMethodSecurity`. Frontend: nuevo módulo `usuarios` (listado + 3 diálogos: ver/crear/modificar), protegido por un `AdminGuard` nuevo que decodifica el claim `roles` ya presente en el JWT, y un ítem de menú que se oculta a quien no sea admin.

**Tech Stack:** Spring Boot 3.5 / JPA (backend), Angular standalone components + `@ng-bootstrap/ng-bootstrap` 19 + `sweetalert2` + `ngx-datatable` (frontend). Testing: JUnit 5 + Mockito + AssertJ + MockMvc (backend), Karma/Jasmine (frontend).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-12-gestion-usuarios-design.md` (repo `dipalza_server`).
- La app móvil no se toca — nunca llama `/api/usuarios/**`, así que la restricción por rol no le afecta.
- `username` es fijo tras la creación — el `PUT` de actualización no lo acepta.
- No se agrega ningún campo "nombre" a `AppUser`. El nombre mostrado para un usuario con vendedor asociado es `Vendedor.nombre`, siempre de solo lectura.
- La creación y edición **no exponen asignación de roles** — todo usuario nuevo queda sin `ROLE_ADMIN`.
- `locked` es 100% manual desde esta pantalla — no se implementa bloqueo automático tras intentos fallidos de login.
- Deshabilitar o bloquear a un usuario debe revocar sus refresh tokens vigentes (reutilizando `RefreshTokenService.revocarTokensDeUsuario`, ya existente) — si no, un usuario ya logueado podría seguir refrescando su sesión pese a estar deshabilitado/bloqueado, ya que ni `AuthController.refresh` ni `webRefresh` chequean `enabled`/`locked` hoy.
- El envío de correo con las credenciales iniciales es best-effort: si falla, el usuario igual queda creado; nunca debe revertir la creación.
- Los DTOs de este plan (`UsuarioDTO`, `CrearUsuarioDTO`, `CrearUsuarioResultDTO`, `ActualizarUsuarioDTO`) y el modelo TypeScript (`Usuario`, `CrearUsuarioPayload`, `CrearUsuarioResult`, `ActualizarUsuarioPayload`) usan exactamente los nombres de campo dados en este plan — deben calzar 1:1 (los records de Java serializan por el nombre del componente, sin prefijo `get`).
- Antes de cada tarea, verificar rama activa: se trabaja en `feat/gestion-usuarios` en ambos repos (`dipalza_server`, `dipalza_web_client`), creada desde `main` actualizado.

---

## Task 1: Backend — restringir `/api/usuarios/**` a `ROLE_ADMIN`

**Files:**
- Modify: `dipalza/src/main/java/cl/eos/dipalza/config/SecurityConfigProdSec.java`
- Modify: `dipalza/src/main/java/cl/eos/dipalza/config/SecurityConfigDevSec.java`

**Interfaces:**
- Produces: regla de autorización que Task 4 (controller) da por hecha — cualquier request a `/api/usuarios/**` sin `ROLE_ADMIN` en sus authorities recibe 403.

- [ ] **Step 1: Agregar la regla en `SecurityConfigProdSec.java`**

En el método `securityFilterChain`, agregar la línea nueva **antes** de
`.requestMatchers("/api/**").authenticated()` (Spring Security usa la
primera regla que matchea, por eso el orden importa):

```java
                    .requestMatchers("/api/usuarios/**").hasRole("ADMIN")
                    .requestMatchers("/api/**").authenticated()
```

- [ ] **Step 2: Agregar la misma regla en `SecurityConfigDevSec.java`**

Mismo cambio, mismo lugar (antes de `.requestMatchers("/api/**").authenticated()`):

```java
                        .requestMatchers("/api/usuarios/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
```

- [ ] **Step 3: Compilar y correr la suite existente**

Run: `cd dipalza && mvn test -Dfrontend.skip=true`
Expected: `BUILD SUCCESS`, sin regresiones — este cambio no debe romper
ningún test existente porque `/api/usuarios/**` no existía antes (nadie
lo llama todavía).

- [ ] **Step 4: Verificación manual (no hay infraestructura de test para el `SecurityFilterChain` completo en este proyecto)**

Documentar en el commit (no hace falta ejecutar esto para completar la
tarea si no hay acceso a un entorno `dev-sec` corriendo, pero sí dejarlo
anotado para quien haga la verificación manual antes de mergear):

```bash
# Sin token -> 401/403
curl -i http://localhost:8080/api/usuarios

# Con token de un usuario SIN ROLE_ADMIN -> 403
curl -i -H "Authorization: Bearer <token-no-admin>" http://localhost:8080/api/usuarios

# Con token de un usuario CON ROLE_ADMIN (ej. lzamora en la BD real) -> 200
curl -i -H "Authorization: Bearer <token-admin>" http://localhost:8080/api/usuarios
```

- [ ] **Step 5: Commit**

```bash
git add dipalza/src/main/java/cl/eos/dipalza/config/SecurityConfigProdSec.java \
        dipalza/src/main/java/cl/eos/dipalza/config/SecurityConfigDevSec.java
git commit -m "feat: restringe /api/usuarios/** a ROLE_ADMIN"
```

---

## Task 2: Backend — DTOs, mapeo `createdAt` y correo de credenciales

**Files:**
- Create: `dipalza/src/main/java/cl/eos/dipalza/model/UsuarioDTO.java`
- Create: `dipalza/src/main/java/cl/eos/dipalza/model/CrearUsuarioDTO.java`
- Create: `dipalza/src/main/java/cl/eos/dipalza/model/CrearUsuarioResultDTO.java`
- Create: `dipalza/src/main/java/cl/eos/dipalza/model/ActualizarUsuarioDTO.java`
- Modify: `dipalza/src/main/java/cl/eos/dipalza/entity/AppUser.java`
- Modify: `dipalza/src/main/java/cl/eos/dipalza/service/EmailService.java`
- Test: `dipalza/src/test/java/cl/eos/dipalza/service/EmailServiceTest.java` (nuevo)

**Interfaces:**
- Produces (usado por Task 3):
  - `UsuarioDTO(Long id, String username, String email, String codigoVendedor, String tipoVendedor, String nombreVendedor, boolean enabled, boolean locked, LocalDate createdAt)`
  - `CrearUsuarioDTO(String username, String email, String codigoVendedor, String tipoVendedor, String password)`
  - `CrearUsuarioResultDTO(UsuarioDTO usuario, boolean correoEnviado)`
  - `ActualizarUsuarioDTO(String email, String codigoVendedor, String tipoVendedor, boolean enabled, boolean locked)`
  - `AppUser.getCreatedAt(): LocalDate`
  - `EmailService.enviarCredencialesIniciales(String destinatario, String username, String claveInicial): void`

- [ ] **Step 1: Crear los 4 DTOs**

`dipalza/src/main/java/cl/eos/dipalza/model/UsuarioDTO.java`:

```java
package cl.eos.dipalza.model;

import java.time.LocalDate;

public record UsuarioDTO(
        Long id,
        String username,
        String email,
        String codigoVendedor,
        String tipoVendedor,
        String nombreVendedor,
        boolean enabled,
        boolean locked,
        LocalDate createdAt
) {}
```

`dipalza/src/main/java/cl/eos/dipalza/model/CrearUsuarioDTO.java`:

```java
package cl.eos.dipalza.model;

public record CrearUsuarioDTO(
        String username,
        String email,
        String codigoVendedor,
        String tipoVendedor,
        String password
) {}
```

`dipalza/src/main/java/cl/eos/dipalza/model/CrearUsuarioResultDTO.java`:

```java
package cl.eos.dipalza.model;

public record CrearUsuarioResultDTO(
        UsuarioDTO usuario,
        boolean correoEnviado
) {}
```

`dipalza/src/main/java/cl/eos/dipalza/model/ActualizarUsuarioDTO.java`:

```java
package cl.eos.dipalza.model;

public record ActualizarUsuarioDTO(
        String email,
        String codigoVendedor,
        String tipoVendedor,
        boolean enabled,
        boolean locked
) {}
```

- [ ] **Step 2: Mapear `createdAt` en `AppUser`**

En `dipalza/src/main/java/cl/eos/dipalza/entity/AppUser.java`, agregar el
import `java.time.LocalDate`, el campo y su getter (la columna
`created_at` ya existe en la BD — ver `01_esquema_ventas.sql` — solo
falta el mapeo Java; es de solo lectura, la BD la define por default):

```java
	@Column(name = "created_at", insertable = false, updatable = false)
	private LocalDate createdAt;
```

```java
	public LocalDate getCreatedAt() {
		return createdAt;
	}
```

(No hace falta setter — nunca se escribe desde la aplicación.)

- [ ] **Step 3: Escribir el test que falla para `EmailService`**

Crear `dipalza/src/test/java/cl/eos/dipalza/service/EmailServiceTest.java`:

```java
package cl.eos.dipalza.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock JavaMailSender mailSender;
    @InjectMocks EmailService service;

    @Test
    void enviarCredencialesIniciales_construyeYEnviaElMensajeCorrecto() {
        service.enviarCredencialesIniciales("nuevo@dipalza.cl", "jperez", "Cl4ve!Segura");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        SimpleMailMessage mensaje = captor.getValue();
        assertThat(mensaje.getTo()).containsExactly("nuevo@dipalza.cl");
        assertThat(mensaje.getSubject()).isEqualTo("Dipalza - Tu cuenta fue creada");
        assertThat(mensaje.getText()).contains("jperez").contains("Cl4ve!Segura");
    }
}
```

- [ ] **Step 4: Ejecutar el test y verificar que falla**

Run: `cd dipalza && mvn test -Dtest=EmailServiceTest`
Expected: FALLA DE COMPILACIÓN — `EmailService` no tiene el método
`enviarCredencialesIniciales`.

- [ ] **Step 5: Implementar `EmailService.enviarCredencialesIniciales`**

En `dipalza/src/main/java/cl/eos/dipalza/service/EmailService.java`,
agregar el método (después de `enviarCodigoRecuperacionClave`):

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

- [ ] **Step 6: Ejecutar el test y verificar que pasa**

Run: `cd dipalza && mvn test -Dtest=EmailServiceTest`
Expected: PASS.

- [ ] **Step 7: Compilar todo el módulo**

Run: `cd dipalza && mvn compile -Dfrontend.skip=true`
Expected: `BUILD SUCCESS` (verifica que los 4 DTOs y el cambio en
`AppUser` compilan, aunque nada los use todavía).

- [ ] **Step 8: Commit**

```bash
git add dipalza/src/main/java/cl/eos/dipalza/model/UsuarioDTO.java \
        dipalza/src/main/java/cl/eos/dipalza/model/CrearUsuarioDTO.java \
        dipalza/src/main/java/cl/eos/dipalza/model/CrearUsuarioResultDTO.java \
        dipalza/src/main/java/cl/eos/dipalza/model/ActualizarUsuarioDTO.java \
        dipalza/src/main/java/cl/eos/dipalza/entity/AppUser.java \
        dipalza/src/main/java/cl/eos/dipalza/service/EmailService.java \
        dipalza/src/test/java/cl/eos/dipalza/service/EmailServiceTest.java
git commit -m "feat: agrega DTOs de usuario, mapeo de createdAt y correo de credenciales iniciales"
```

---

## Task 3: Backend — `UsuarioAdminService`

**Files:**
- Create: `dipalza/src/main/java/cl/eos/dipalza/service/UsuarioAdminService.java`
- Test: `dipalza/src/test/java/cl/eos/dipalza/service/UsuarioAdminServiceTest.java`

**Interfaces:**
- Consumes: `UserRepo` (`findByUsername`, `findByEmail`, `findById`, `findAll`, `save`, ya existentes), `VendedorRepository` (`findById(VendedorId)`, heredado de `JpaRepository`), `PasswordEncoder` (`encode`, ya usado en otros controllers), `EmailService.enviarCredencialesIniciales` (Task 2), `RefreshTokenService.revocarTokensDeUsuario` (ya existente), los 4 DTOs de Task 2.
- Produces (usado por Task 4):
  - `UsuarioAdminService.listar(): List<UsuarioDTO>`
  - `.obtener(Long id): UsuarioDTO` — 404 si no existe
  - `.crear(CrearUsuarioDTO): CrearUsuarioResultDTO` — 400 si username vacío/duplicado, email duplicado, password &lt; 8 caracteres, o vendedor inexistente/incompleto
  - `.actualizar(Long id, ActualizarUsuarioDTO): UsuarioDTO` — 404 si no existe, 400 si el email ya lo tiene otro usuario
  - `.habilitar(Long id): UsuarioDTO`, `.deshabilitar(Long id): UsuarioDTO`, `.bloquear(Long id): UsuarioDTO`, `.desbloquear(Long id): UsuarioDTO` — 404 si no existe

- [ ] **Step 1: Escribir los tests que fallan para `UsuarioAdminService`**

Crear `dipalza/src/test/java/cl/eos/dipalza/service/UsuarioAdminServiceTest.java`:

```java
package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.AppUser;
import cl.eos.dipalza.entity.Vendedor;
import cl.eos.dipalza.entity.ids.VendedorId;
import cl.eos.dipalza.model.ActualizarUsuarioDTO;
import cl.eos.dipalza.model.CrearUsuarioDTO;
import cl.eos.dipalza.model.CrearUsuarioResultDTO;
import cl.eos.dipalza.model.UsuarioDTO;
import cl.eos.dipalza.repository.UserRepo;
import cl.eos.dipalza.repository.VendedorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UsuarioAdminServiceTest {

    @Mock UserRepo userRepo;
    @Mock VendedorRepository vendedorRepo;
    @Mock PasswordEncoder passwordEncoder;
    @Mock EmailService emailService;
    @Mock RefreshTokenService refreshTokenService;
    @InjectMocks UsuarioAdminService service;

    private AppUser usuario(Long id, String username, String email, boolean enabled, boolean locked) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setUsername(username);
        u.setEmail(email);
        u.setPassword("hash");
        u.setEnabled(enabled);
        u.setLocked(locked);
        return u;
    }

    private Vendedor vendedor(String codigo, String tipo, String nombre) {
        Vendedor v = new Vendedor(new VendedorId(codigo, tipo));
        v.setNombre(nombre);
        return v;
    }

    @Test
    void listar_retornaTodosMapeados() {
        when(userRepo.findAll()).thenReturn(List.of(usuario(1L, "jperez", "j@dipalza.cl", true, false)));

        List<UsuarioDTO> result = service.listar();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).username()).isEqualTo("jperez");
        assertThat(result.get(0).codigoVendedor()).isNull();
    }

    @Test
    void obtener_existente_retornaDto() {
        when(userRepo.findById(1L)).thenReturn(Optional.of(usuario(1L, "jperez", "j@dipalza.cl", true, false)));

        UsuarioDTO dto = service.obtener(1L);

        assertThat(dto.id()).isEqualTo(1L);
        assertThat(dto.username()).isEqualTo("jperez");
    }

    @Test
    void obtener_noExistente_lanza404() {
        when(userRepo.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.obtener(99L)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void crear_usernameDuplicado_lanza400() {
        when(userRepo.findByUsername("jperez")).thenReturn(Optional.of(usuario(1L, "jperez", null, true, false)));

        assertThatThrownBy(() -> service.crear(new CrearUsuarioDTO("jperez", null, null, null, "claveLarga1")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Ya existe un usuario con ese username");

        verify(userRepo, never()).save(any());
    }

    @Test
    void crear_emailDuplicado_lanza400() {
        when(userRepo.findByUsername("nuevo")).thenReturn(Optional.empty());
        when(userRepo.findByEmail("dup@dipalza.cl")).thenReturn(Optional.of(usuario(1L, "otro", "dup@dipalza.cl", true, false)));

        assertThatThrownBy(() -> service.crear(new CrearUsuarioDTO("nuevo", "dup@dipalza.cl", null, null, "claveLarga1")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Ya existe un usuario con ese correo");

        verify(userRepo, never()).save(any());
    }

    @Test
    void crear_passwordCorta_lanza400() {
        when(userRepo.findByUsername("nuevo")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.crear(new CrearUsuarioDTO("nuevo", null, null, null, "corta")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("al menos 8 caracteres");

        verify(userRepo, never()).save(any());
    }

    @Test
    void crear_vendedorInexistente_lanza400() {
        when(userRepo.findByUsername("nuevo")).thenReturn(Optional.empty());
        when(vendedorRepo.findById(new VendedorId("001", "0"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.crear(new CrearUsuarioDTO("nuevo", null, "001", "0", "claveLarga1")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("vendedor indicado no existe");
    }

    @Test
    void crear_vendedorSoloCodigoSinTipo_lanza400() {
        when(userRepo.findByUsername("nuevo")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.crear(new CrearUsuarioDTO("nuevo", null, "001", null, "claveLarga1")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("código y tipo de vendedor juntos");
    }

    @Test
    void crear_exitoso_encriptaPasswordYGuarda() {
        when(userRepo.findByUsername("nuevo")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("claveLarga1")).thenReturn("hashEncriptado");
        when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        CrearUsuarioResultDTO result = service.crear(new CrearUsuarioDTO("nuevo", null, null, null, "claveLarga1"));

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepo).save(captor.capture());
        assertThat(captor.getValue().getPassword()).isEqualTo("hashEncriptado");
        assertThat(captor.getValue().isEnabled()).isTrue();
        assertThat(captor.getValue().isLocked()).isFalse();
        assertThat(result.usuario().username()).isEqualTo("nuevo");
        assertThat(result.correoEnviado()).isFalse();
    }

    @Test
    void crear_sinEmail_noIntentaEnviarCorreo() {
        when(userRepo.findByUsername("nuevo")).thenReturn(Optional.empty());
        when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        service.crear(new CrearUsuarioDTO("nuevo", null, null, null, "claveLarga1"));

        verify(emailService, never()).enviarCredencialesIniciales(anyString(), anyString(), anyString());
    }

    @Test
    void crear_conEmailYEnvioExitoso_correoEnviadoTrue() {
        when(userRepo.findByUsername("nuevo")).thenReturn(Optional.empty());
        when(userRepo.findByEmail("nuevo@dipalza.cl")).thenReturn(Optional.empty());
        when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        CrearUsuarioResultDTO result = service.crear(
                new CrearUsuarioDTO("nuevo", "nuevo@dipalza.cl", null, null, "claveLarga1"));

        verify(emailService).enviarCredencialesIniciales("nuevo@dipalza.cl", "nuevo", "claveLarga1");
        assertThat(result.correoEnviado()).isTrue();
    }

    @Test
    void crear_conEmailYEnvioFalla_usuarioQuedaCreadoCorreoEnviadoFalse() {
        when(userRepo.findByUsername("nuevo")).thenReturn(Optional.empty());
        when(userRepo.findByEmail("nuevo@dipalza.cl")).thenReturn(Optional.empty());
        when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("SMTP down")).when(emailService)
                .enviarCredencialesIniciales(anyString(), anyString(), anyString());

        CrearUsuarioResultDTO result = service.crear(
                new CrearUsuarioDTO("nuevo", "nuevo@dipalza.cl", null, null, "claveLarga1"));

        assertThat(result.correoEnviado()).isFalse();
        assertThat(result.usuario().username()).isEqualTo("nuevo");
        verify(userRepo).save(any(AppUser.class));
    }

    @Test
    void actualizar_noExistente_lanza404() {
        when(userRepo.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.actualizar(99L, new ActualizarUsuarioDTO(null, null, null, true, false)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void actualizar_emailDuplicadoDeOtroUsuario_lanza400() {
        AppUser objetivo = usuario(1L, "jperez", "j@dipalza.cl", true, false);
        AppUser otro = usuario(2L, "otro", "dup@dipalza.cl", true, false);
        when(userRepo.findById(1L)).thenReturn(Optional.of(objetivo));
        when(userRepo.findByEmail("dup@dipalza.cl")).thenReturn(Optional.of(otro));

        assertThatThrownBy(() -> service.actualizar(1L, new ActualizarUsuarioDTO("dup@dipalza.cl", null, null, true, false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Ya existe un usuario con ese correo");
    }

    @Test
    void actualizar_quitaVendedor_setVendedorNull() {
        AppUser objetivo = usuario(1L, "jperez", "j@dipalza.cl", true, false);
        objetivo.setVendedor(vendedor("001", "0", "Juan Perez"));
        when(userRepo.findById(1L)).thenReturn(Optional.of(objetivo));
        when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        UsuarioDTO result = service.actualizar(1L, new ActualizarUsuarioDTO("j@dipalza.cl", null, null, true, false));

        assertThat(result.codigoVendedor()).isNull();
    }

    @Test
    void actualizar_deshabilita_revocaTokens() {
        AppUser objetivo = usuario(1L, "jperez", "j@dipalza.cl", true, false);
        when(userRepo.findById(1L)).thenReturn(Optional.of(objetivo));
        when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        service.actualizar(1L, new ActualizarUsuarioDTO("j@dipalza.cl", null, null, false, false));

        verify(refreshTokenService).revocarTokensDeUsuario(objetivo);
    }

    @Test
    void actualizar_sinCambiosDeAcceso_noRevocaTokens() {
        AppUser objetivo = usuario(1L, "jperez", "j@dipalza.cl", true, false);
        when(userRepo.findById(1L)).thenReturn(Optional.of(objetivo));
        when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        service.actualizar(1L, new ActualizarUsuarioDTO("j@dipalza.cl", null, null, true, false));

        verify(refreshTokenService, never()).revocarTokensDeUsuario(any());
    }

    @Test
    void habilitar_actualizaFlag() {
        AppUser objetivo = usuario(1L, "jperez", null, false, false);
        when(userRepo.findById(1L)).thenReturn(Optional.of(objetivo));
        when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        UsuarioDTO result = service.habilitar(1L);

        assertThat(result.enabled()).isTrue();
        verify(refreshTokenService, never()).revocarTokensDeUsuario(any());
    }

    @Test
    void deshabilitar_revocaTokens() {
        AppUser objetivo = usuario(1L, "jperez", null, true, false);
        when(userRepo.findById(1L)).thenReturn(Optional.of(objetivo));
        when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        UsuarioDTO result = service.deshabilitar(1L);

        assertThat(result.enabled()).isFalse();
        verify(refreshTokenService).revocarTokensDeUsuario(objetivo);
    }

    @Test
    void bloquear_revocaTokens() {
        AppUser objetivo = usuario(1L, "jperez", null, true, false);
        when(userRepo.findById(1L)).thenReturn(Optional.of(objetivo));
        when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        UsuarioDTO result = service.bloquear(1L);

        assertThat(result.locked()).isTrue();
        verify(refreshTokenService).revocarTokensDeUsuario(objetivo);
    }

    @Test
    void desbloquear_actualizaFlagSinRevocar() {
        AppUser objetivo = usuario(1L, "jperez", null, true, true);
        when(userRepo.findById(1L)).thenReturn(Optional.of(objetivo));
        when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        UsuarioDTO result = service.desbloquear(1L);

        assertThat(result.locked()).isFalse();
        verify(refreshTokenService, never()).revocarTokensDeUsuario(any());
    }
}
```

- [ ] **Step 2: Ejecutar los tests y verificar que fallan**

Run: `cd dipalza && mvn test -Dtest=UsuarioAdminServiceTest`
Expected: FALLA DE COMPILACIÓN — `UsuarioAdminService` no existe.

- [ ] **Step 3: Implementar `UsuarioAdminService`**

Crear `dipalza/src/main/java/cl/eos/dipalza/service/UsuarioAdminService.java`:

```java
package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.AppUser;
import cl.eos.dipalza.entity.Vendedor;
import cl.eos.dipalza.entity.ids.VendedorId;
import cl.eos.dipalza.model.ActualizarUsuarioDTO;
import cl.eos.dipalza.model.CrearUsuarioDTO;
import cl.eos.dipalza.model.CrearUsuarioResultDTO;
import cl.eos.dipalza.model.UsuarioDTO;
import cl.eos.dipalza.repository.UserRepo;
import cl.eos.dipalza.repository.VendedorRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class UsuarioAdminService {

    private static final int CLAVE_LARGO_MINIMO = 8;

    private final UserRepo userRepo;
    private final VendedorRepository vendedorRepo;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final RefreshTokenService refreshTokenService;

    public UsuarioAdminService(UserRepo userRepo, VendedorRepository vendedorRepo, PasswordEncoder passwordEncoder,
            EmailService emailService, RefreshTokenService refreshTokenService) {
        this.userRepo = userRepo;
        this.vendedorRepo = vendedorRepo;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional(readOnly = true)
    public List<UsuarioDTO> listar() {
        return userRepo.findAll().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public UsuarioDTO obtener(Long id) {
        return toDto(buscarOLanzar(id));
    }

    @Transactional
    public CrearUsuarioResultDTO crear(CrearUsuarioDTO req) {
        if (req.username() == null || req.username().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El username es obligatorio");
        if (userRepo.findByUsername(req.username()).isPresent())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ya existe un usuario con ese username");
        String email = blankToNull(req.email());
        if (email != null && userRepo.findByEmail(email).isPresent())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ya existe un usuario con ese correo");
        if (req.password() == null || req.password().length() < CLAVE_LARGO_MINIMO)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La clave debe tener al menos " + CLAVE_LARGO_MINIMO + " caracteres");

        Vendedor vendedor = resolverVendedor(req.codigoVendedor(), req.tipoVendedor());

        AppUser u = new AppUser();
        u.setUsername(req.username());
        u.setEmail(email);
        u.setPassword(passwordEncoder.encode(req.password()));
        u.setEnabled(true);
        u.setLocked(false);
        u.setVendedor(vendedor);
        userRepo.save(u);

        boolean correoEnviado = false;
        if (email != null) {
            try {
                emailService.enviarCredencialesIniciales(email, u.getUsername(), req.password());
                correoEnviado = true;
            } catch (RuntimeException e) {
                correoEnviado = false;
            }
        }

        return new CrearUsuarioResultDTO(toDto(u), correoEnviado);
    }

    @Transactional
    public UsuarioDTO actualizar(Long id, ActualizarUsuarioDTO req) {
        AppUser u = buscarOLanzar(id);

        String email = blankToNull(req.email());
        if (email != null) {
            userRepo.findByEmail(email)
                    .filter(existente -> !existente.getId().equals(id))
                    .ifPresent(existente -> {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ya existe un usuario con ese correo");
                    });
        }
        u.setEmail(email);
        u.setVendedor(resolverVendedor(req.codigoVendedor(), req.tipoVendedor()));

        boolean seRestringeAcceso = (u.isEnabled() && !req.enabled()) || (!u.isLocked() && req.locked());
        u.setEnabled(req.enabled());
        u.setLocked(req.locked());
        userRepo.save(u);

        if (seRestringeAcceso) {
            refreshTokenService.revocarTokensDeUsuario(u);
        }

        return toDto(u);
    }

    @Transactional
    public UsuarioDTO habilitar(Long id) {
        AppUser u = buscarOLanzar(id);
        u.setEnabled(true);
        userRepo.save(u);
        return toDto(u);
    }

    @Transactional
    public UsuarioDTO deshabilitar(Long id) {
        AppUser u = buscarOLanzar(id);
        u.setEnabled(false);
        userRepo.save(u);
        refreshTokenService.revocarTokensDeUsuario(u);
        return toDto(u);
    }

    @Transactional
    public UsuarioDTO bloquear(Long id) {
        AppUser u = buscarOLanzar(id);
        u.setLocked(true);
        userRepo.save(u);
        refreshTokenService.revocarTokensDeUsuario(u);
        return toDto(u);
    }

    @Transactional
    public UsuarioDTO desbloquear(Long id) {
        AppUser u = buscarOLanzar(id);
        u.setLocked(false);
        userRepo.save(u);
        return toDto(u);
    }

    private AppUser buscarOLanzar(Long id) {
        return userRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado"));
    }

    private Vendedor resolverVendedor(String codigo, String tipo) {
        boolean tieneCodigo = codigo != null && !codigo.isBlank();
        boolean tieneTipo = tipo != null && !tipo.isBlank();
        if (!tieneCodigo && !tieneTipo) {
            return null;
        }
        if (tieneCodigo != tieneTipo) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Debe indicar código y tipo de vendedor juntos");
        }
        return vendedorRepo.findById(new VendedorId(codigo, tipo))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "El vendedor indicado no existe"));
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private UsuarioDTO toDto(AppUser u) {
        Vendedor v = u.getVendedor();
        return new UsuarioDTO(
                u.getId(),
                u.getUsername(),
                u.getEmail(),
                v != null ? v.getId().getCodigo() : null,
                v != null ? v.getId().getTipo() : null,
                v != null ? v.getNombre() : null,
                u.isEnabled(),
                u.isLocked(),
                u.getCreatedAt()
        );
    }
}
```

- [ ] **Step 4: Ejecutar los tests y verificar que pasan**

Run: `cd dipalza && mvn test -Dtest=UsuarioAdminServiceTest`
Expected: PASS (21 tests).

- [ ] **Step 5: Commit**

```bash
git add dipalza/src/main/java/cl/eos/dipalza/service/UsuarioAdminService.java \
        dipalza/src/test/java/cl/eos/dipalza/service/UsuarioAdminServiceTest.java
git commit -m "feat: agrega UsuarioAdminService con alta, edición, habilitar/deshabilitar y bloquear/desbloquear"
```

---

## Task 4: Backend — `UsuarioAdminController`

**Files:**
- Create: `dipalza/src/main/java/cl/eos/dipalza/controller/UsuarioAdminController.java`
- Test: `dipalza/src/test/java/cl/eos/dipalza/controller/UsuarioAdminControllerTest.java`

**Interfaces:**
- Consumes: `UsuarioAdminService` (Task 3), los 4 DTOs (Task 2).
- Produces (usado por el frontend, Task 6 en adelante):
  - `GET /api/usuarios` → 200, `List<UsuarioDTO>`
  - `GET /api/usuarios/{id}` → 200 `UsuarioDTO`, 404 si no existe
  - `POST /api/usuarios` → 200 `CrearUsuarioResultDTO`, 400 en validaciones
  - `PUT /api/usuarios/{id}` → 200 `UsuarioDTO`, 404/400
  - `PATCH /api/usuarios/{id}/habilitar` → 200 `UsuarioDTO`
  - `PATCH /api/usuarios/{id}/deshabilitar` → 200 `UsuarioDTO`
  - `PATCH /api/usuarios/{id}/bloquear` → 200 `UsuarioDTO`
  - `PATCH /api/usuarios/{id}/desbloquear` → 200 `UsuarioDTO`

- [ ] **Step 1: Escribir los tests que fallan para el controller**

Crear `dipalza/src/test/java/cl/eos/dipalza/controller/UsuarioAdminControllerTest.java`:

```java
package cl.eos.dipalza.controller;

import cl.eos.dipalza.model.ActualizarUsuarioDTO;
import cl.eos.dipalza.model.CrearUsuarioDTO;
import cl.eos.dipalza.model.CrearUsuarioResultDTO;
import cl.eos.dipalza.model.UsuarioDTO;
import cl.eos.dipalza.service.UsuarioAdminService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = UsuarioAdminController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
class UsuarioAdminControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean UsuarioAdminService service;

    private UsuarioDTO dto(Long id) {
        return new UsuarioDTO(id, "jperez", "j@dipalza.cl", null, null, null, true, false, null);
    }

    @Test
    void listar_retornaLista() throws Exception {
        when(service.listar()).thenReturn(List.of(dto(1L)));
        mockMvc.perform(get("/api/usuarios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].username", is("jperez")));
    }

    @Test
    void obtener_existente_retornaDto() throws Exception {
        when(service.obtener(1L)).thenReturn(dto(1L));
        mockMvc.perform(get("/api/usuarios/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(1)));
    }

    @Test
    void obtener_noExistente_retorna404() throws Exception {
        when(service.obtener(99L)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado"));
        mockMvc.perform(get("/api/usuarios/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void crear_valido_retornaResultado() throws Exception {
        CrearUsuarioDTO req = new CrearUsuarioDTO("nuevo", "n@dipalza.cl", null, null, "claveLarga1");
        when(service.crear(any())).thenReturn(new CrearUsuarioResultDTO(dto(1L), true));

        mockMvc.perform(post("/api/usuarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario.id", is(1)))
                .andExpect(jsonPath("$.correoEnviado", is(true)));
    }

    @Test
    void crear_usernameDuplicado_retorna400() throws Exception {
        when(service.crear(any())).thenThrow(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ya existe un usuario con ese username"));

        mockMvc.perform(post("/api/usuarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CrearUsuarioDTO("jperez", null, null, null, "claveLarga1"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void actualizar_valido_retornaDto() throws Exception {
        ActualizarUsuarioDTO req = new ActualizarUsuarioDTO("j@dipalza.cl", null, null, true, false);
        when(service.actualizar(eq(1L), any())).thenReturn(dto(1L));

        mockMvc.perform(put("/api/usuarios/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username", is("jperez")));
    }

    @Test
    void actualizar_noExistente_retorna404() throws Exception {
        when(service.actualizar(eq(99L), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado"));

        mockMvc.perform(put("/api/usuarios/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ActualizarUsuarioDTO(null, null, null, true, false))))
                .andExpect(status().isNotFound());
    }

    @Test
    void habilitar_retornaDto() throws Exception {
        when(service.habilitar(1L)).thenReturn(dto(1L));
        mockMvc.perform(patch("/api/usuarios/1/habilitar"))
                .andExpect(status().isOk());
        verify(service).habilitar(1L);
    }

    @Test
    void deshabilitar_retornaDto() throws Exception {
        when(service.deshabilitar(1L)).thenReturn(dto(1L));
        mockMvc.perform(patch("/api/usuarios/1/deshabilitar"))
                .andExpect(status().isOk());
        verify(service).deshabilitar(1L);
    }

    @Test
    void bloquear_retornaDto() throws Exception {
        when(service.bloquear(1L)).thenReturn(dto(1L));
        mockMvc.perform(patch("/api/usuarios/1/bloquear"))
                .andExpect(status().isOk());
        verify(service).bloquear(1L);
    }

    @Test
    void desbloquear_retornaDto() throws Exception {
        when(service.desbloquear(1L)).thenReturn(dto(1L));
        mockMvc.perform(patch("/api/usuarios/1/desbloquear"))
                .andExpect(status().isOk());
        verify(service).desbloquear(1L);
    }
}
```

- [ ] **Step 2: Ejecutar los tests y verificar que fallan**

Run: `cd dipalza && mvn test -Dtest=UsuarioAdminControllerTest`
Expected: FALLA — `UsuarioAdminController` no existe.

- [ ] **Step 3: Implementar `UsuarioAdminController`**

Crear `dipalza/src/main/java/cl/eos/dipalza/controller/UsuarioAdminController.java`:

```java
package cl.eos.dipalza.controller;

import cl.eos.dipalza.model.ActualizarUsuarioDTO;
import cl.eos.dipalza.model.CrearUsuarioDTO;
import cl.eos.dipalza.model.CrearUsuarioResultDTO;
import cl.eos.dipalza.model.UsuarioDTO;
import cl.eos.dipalza.service.UsuarioAdminService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/usuarios")
@Profile({ "dev-sec", "prod-sec" })
public class UsuarioAdminController {

    private final UsuarioAdminService service;

    public UsuarioAdminController(UsuarioAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<UsuarioDTO> listar() {
        return service.listar();
    }

    @GetMapping("/{id}")
    public UsuarioDTO obtener(@PathVariable Long id) {
        return service.obtener(id);
    }

    @PostMapping
    public CrearUsuarioResultDTO crear(@RequestBody CrearUsuarioDTO req) {
        return service.crear(req);
    }

    @PutMapping("/{id}")
    public UsuarioDTO actualizar(@PathVariable Long id, @RequestBody ActualizarUsuarioDTO req) {
        return service.actualizar(id, req);
    }

    @PatchMapping("/{id}/habilitar")
    public UsuarioDTO habilitar(@PathVariable Long id) {
        return service.habilitar(id);
    }

    @PatchMapping("/{id}/deshabilitar")
    public UsuarioDTO deshabilitar(@PathVariable Long id) {
        return service.deshabilitar(id);
    }

    @PatchMapping("/{id}/bloquear")
    public UsuarioDTO bloquear(@PathVariable Long id) {
        return service.bloquear(id);
    }

    @PatchMapping("/{id}/desbloquear")
    public UsuarioDTO desbloquear(@PathVariable Long id) {
        return service.desbloquear(id);
    }
}
```

- [ ] **Step 4: Ejecutar los tests y verificar que pasan**

Run: `cd dipalza && mvn test -Dtest=UsuarioAdminControllerTest`
Expected: PASS (11 tests).

- [ ] **Step 5: Ejecutar toda la suite de backend**

Run: `cd dipalza && mvn test -Dfrontend.skip=true`
Expected: `BUILD SUCCESS`, sin regresiones.

- [ ] **Step 6: Commit**

```bash
git add dipalza/src/main/java/cl/eos/dipalza/controller/UsuarioAdminController.java \
        dipalza/src/test/java/cl/eos/dipalza/controller/UsuarioAdminControllerTest.java
git commit -m "feat: expone endpoints REST de gestión de usuarios"
```

---

## Task 5: Frontend — `AuthService.isAdmin()` y `AdminGuard`

**Files:**
- Modify: `src/app/core/service/auth.service.ts`
- Create: `src/app/core/guard/admin.guard.ts`
- Create: `src/app/core/service/auth.service.spec.ts`
- Test: `src/app/core/guard/admin.guard.spec.ts`

**Interfaces:**
- Consumes: `AuthService.getToken()` (ya existe).
- Produces (usado por Task 11):
  - `AuthService.isAdmin(): boolean`
  - `AdminGuard` (standalone, `providedIn: 'root'`), con `canActivate(): boolean`.

- [ ] **Step 1: Escribir el spec que falla para `AuthService.isAdmin()`**

Crear `src/app/core/service/auth.service.spec.ts`:

```ts
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from './auth.service';

function fakeJwt(payload: object): string {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const body = btoa(JSON.stringify(payload));
  return `${header}.${body}.firma-invalida`;
}

describe('AuthService.isAdmin', () => {
  let service: AuthService;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(AuthService);
  });

  it('retorna false si no hay usuario logueado', () => {
    expect(service.isAdmin()).toBeFalse();
  });

  it('retorna false si el token no tiene ROLE_ADMIN entre los roles', () => {
    const token = fakeJwt({ roles: ['ROLE_VENDEDOR'] });
    localStorage.setItem('currentUser', JSON.stringify({ token }));
    service = TestBed.inject(AuthService);
    expect(service.isAdmin()).toBeFalse();
  });

  it('retorna true si el token tiene ROLE_ADMIN entre los roles', () => {
    const token = fakeJwt({ roles: ['ROLE_ADMIN', 'ROLE_VENDEDOR'] });
    localStorage.setItem('currentUser', JSON.stringify({ token }));
    service = TestBed.inject(AuthService);
    expect(service.isAdmin()).toBeTrue();
  });

  it('retorna false si el token está mal formado', () => {
    localStorage.setItem('currentUser', JSON.stringify({ token: 'no-es-un-jwt' }));
    service = TestBed.inject(AuthService);
    expect(service.isAdmin()).toBeFalse();
  });
});
```

- [ ] **Step 2: Ejecutar el spec y verificar que falla**

Run: `ng test --include='**/auth.service.spec.ts'`
Expected: FALLA — `isAdmin` no existe en `AuthService`.

- [ ] **Step 3: Implementar `AuthService.isAdmin()`**

En `src/app/core/service/auth.service.ts`, agregar el método (por
ejemplo después de `getToken()`):

```ts
isAdmin(): boolean {
  const token = this.getToken();
  if (!token) {
    return false;
  }
  try {
    const payload = JSON.parse(atob(token.split('.')[1]));
    const roles: string[] = payload.roles ?? [];
    return roles.includes('ROLE_ADMIN');
  } catch {
    return false;
  }
}
```

- [ ] **Step 4: Ejecutar el spec y verificar que pasa**

Run: `ng test --include='**/auth.service.spec.ts'`
Expected: PASS (4 tests).

- [ ] **Step 5: Escribir el spec que falla para `AdminGuard`**

Crear `src/app/core/guard/admin.guard.spec.ts`:

```ts
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { AdminGuard } from './admin.guard';
import { AuthService } from '../service/auth.service';

describe('AdminGuard', () => {
  let guard: AdminGuard;
  let authServiceSpy: jasmine.SpyObj<AuthService>;
  let routerSpy: jasmine.SpyObj<Router>;

  beforeEach(() => {
    authServiceSpy = jasmine.createSpyObj('AuthService', ['isAdmin']);
    routerSpy = jasmine.createSpyObj('Router', ['navigate']);

    TestBed.configureTestingModule({
      providers: [
        AdminGuard,
        { provide: AuthService, useValue: authServiceSpy },
        { provide: Router, useValue: routerSpy }
      ]
    });
    guard = TestBed.inject(AdminGuard);
  });

  it('permite el acceso si el usuario es admin', () => {
    authServiceSpy.isAdmin.and.returnValue(true);
    expect(guard.canActivate()).toBeTrue();
    expect(routerSpy.navigate).not.toHaveBeenCalled();
  });

  it('redirige y bloquea el acceso si el usuario no es admin', () => {
    authServiceSpy.isAdmin.and.returnValue(false);
    expect(guard.canActivate()).toBeFalse();
    expect(routerSpy.navigate).toHaveBeenCalledWith(['/']);
  });
});
```

- [ ] **Step 6: Ejecutar el spec y verificar que falla**

Run: `ng test --include='**/admin.guard.spec.ts'`
Expected: FALLA — el archivo `admin.guard.ts` no existe.

- [ ] **Step 7: Implementar `AdminGuard`**

Crear `src/app/core/guard/admin.guard.ts`:

```ts
import { Injectable } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../service/auth.service';

@Injectable({
  providedIn: 'root',
})
export class AdminGuard {
  constructor(private authService: AuthService, private router: Router) {}

  canActivate(): boolean {
    if (this.authService.isAdmin()) {
      return true;
    }
    this.router.navigate(['/']);
    return false;
  }
}
```

- [ ] **Step 8: Ejecutar el spec y verificar que pasa**

Run: `ng test --include='**/admin.guard.spec.ts'`
Expected: PASS (2 tests).

- [ ] **Step 9: Commit**

```bash
git add src/app/core/service/auth.service.ts \
        src/app/core/service/auth.service.spec.ts \
        src/app/core/guard/admin.guard.ts \
        src/app/core/guard/admin.guard.spec.ts
git commit -m "feat: agrega AuthService.isAdmin() y AdminGuard"
```

---

## Task 6: Frontend — modelo y `UsuariosService`

**Files:**
- Create: `src/app/usuarios/models/model.ts`
- Create: `src/app/usuarios/usuarios.service.ts`

**Interfaces:**
- Produces (usado por Tasks 7-10):
  - `interface Usuario { id: number; username: string; email: string | null; codigoVendedor: string | null; tipoVendedor: string | null; nombreVendedor: string | null; enabled: boolean; locked: boolean; createdAt: string | null; }`
  - `interface CrearUsuarioPayload { username: string; email?: string; codigoVendedor?: string; tipoVendedor?: string; password: string; }`
  - `interface CrearUsuarioResult { usuario: Usuario; correoEnviado: boolean; }`
  - `interface ActualizarUsuarioPayload { email?: string; codigoVendedor?: string; tipoVendedor?: string; enabled: boolean; locked: boolean; }`
  - `UsuariosService.listar(): Observable<Usuario[]>`
  - `.obtener(id: number): Observable<Usuario>`
  - `.crear(payload: CrearUsuarioPayload): Observable<CrearUsuarioResult>`
  - `.actualizar(id: number, payload: ActualizarUsuarioPayload): Observable<Usuario>`
  - `.habilitar(id: number): Observable<Usuario>`, `.deshabilitar(id: number): Observable<Usuario>`, `.bloquear(id: number): Observable<Usuario>`, `.desbloquear(id: number): Observable<Usuario>`

No lleva TDD propio — son tipos e HTTP wrappers delgados sin lógica
(mismo criterio que `VentasService`, que tampoco tiene spec dedicado);
quedan ejercitados por los specs de los componentes de las Tasks 7-10.

- [ ] **Step 1: Crear el modelo**

Crear `src/app/usuarios/models/model.ts`:

```ts
export interface Usuario {
  id: number;
  username: string;
  email: string | null;
  codigoVendedor: string | null;
  tipoVendedor: string | null;
  nombreVendedor: string | null;
  enabled: boolean;
  locked: boolean;
  createdAt: string | null;
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

- [ ] **Step 2: Crear `UsuariosService`**

Crear `src/app/usuarios/usuarios.service.ts`:

```ts
import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from 'environments/environment';
import { ActualizarUsuarioPayload, CrearUsuarioPayload, CrearUsuarioResult, Usuario } from './models/model';

@Injectable({
  providedIn: 'root'
})
export class UsuariosService {
  private urlUsuarios = `${environment.apiUrl}/usuarios`;

  constructor(private httpClient: HttpClient) {}

  listar(): Observable<Usuario[]> {
    return this.httpClient.get<Usuario[]>(this.urlUsuarios);
  }

  obtener(id: number): Observable<Usuario> {
    return this.httpClient.get<Usuario>(`${this.urlUsuarios}/${id}`);
  }

  crear(payload: CrearUsuarioPayload): Observable<CrearUsuarioResult> {
    return this.httpClient.post<CrearUsuarioResult>(this.urlUsuarios, payload);
  }

  actualizar(id: number, payload: ActualizarUsuarioPayload): Observable<Usuario> {
    return this.httpClient.put<Usuario>(`${this.urlUsuarios}/${id}`, payload);
  }

  habilitar(id: number): Observable<Usuario> {
    return this.httpClient.patch<Usuario>(`${this.urlUsuarios}/${id}/habilitar`, {});
  }

  deshabilitar(id: number): Observable<Usuario> {
    return this.httpClient.patch<Usuario>(`${this.urlUsuarios}/${id}/deshabilitar`, {});
  }

  bloquear(id: number): Observable<Usuario> {
    return this.httpClient.patch<Usuario>(`${this.urlUsuarios}/${id}/bloquear`, {});
  }

  desbloquear(id: number): Observable<Usuario> {
    return this.httpClient.patch<Usuario>(`${this.urlUsuarios}/${id}/desbloquear`, {});
  }
}
```

- [ ] **Step 3: Verificar que compila**

Run: `npx tsc -p tsconfig.app.json --noEmit`
Expected: sin errores.

- [ ] **Step 4: Commit**

```bash
git add src/app/usuarios/models/model.ts src/app/usuarios/usuarios.service.ts
git commit -m "feat: agrega modelo y UsuariosService"
```

---

## Task 7: Frontend — diálogo `CrearUsuarioComponent`

**Files:**
- Create: `src/app/usuarios/crear-usuario/crear-usuario.component.ts`
- Create: `src/app/usuarios/crear-usuario/crear-usuario.component.html`
- Create: `src/app/usuarios/crear-usuario/crear-usuario.component.scss` (vacío)
- Test: `src/app/usuarios/crear-usuario/crear-usuario.component.spec.ts`

**Interfaces:**
- Consumes: `UsuariosService.crear` (Task 6), `VendedorService.getVendedores()` y `VendedorDTO` (ya existentes en `app/mapa/vendedor.service.ts` y `app/mapa/models/model.ts` — **no crear un servicio nuevo, reutilizar este**).
- Produces (usado por Task 9): `CrearUsuarioComponent` (standalone), cierra el modal con `activeModal.close(result: CrearUsuarioResult)` al guardar con éxito.

- [ ] **Step 1: Escribir el spec que falla**

Crear `src/app/usuarios/crear-usuario/crear-usuario.component.spec.ts`:

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { NgbActiveModal } from '@ng-bootstrap/ng-bootstrap';
import { environment } from 'environments/environment';

import { CrearUsuarioComponent } from './crear-usuario.component';
import { VendedorDTO } from 'app/mapa/models/model';
import { CrearUsuarioResult } from '../models/model';

describe('CrearUsuarioComponent', () => {
  let component: CrearUsuarioComponent;
  let fixture: ComponentFixture<CrearUsuarioComponent>;
  let httpMock: HttpTestingController;

  const vendedor: VendedorDTO = { codigo: '001', tipo: '0', nombre: 'Juan Perez' } as VendedorDTO;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CrearUsuarioComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), NgbActiveModal]
    }).compileComponents();

    fixture = TestBed.createComponent(CrearUsuarioComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    httpMock.expectOne(`${environment.apiUrl}/vendedores`).flush([vendedor]);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('generarClave rellena el campo password con al menos 8 caracteres', () => {
    component.generarClave();
    expect(component.form.get('password')?.value.length).toBeGreaterThanOrEqual(8);
  });

  it('crea el usuario y cierra el modal con el resultado', () => {
    component.form.patchValue({ username: 'nuevo', email: 'nuevo@dipalza.cl', password: 'claveLarga1' });
    component.vendedorSeleccionado = vendedor;
    const closeSpy = spyOn(component.activeModal, 'close');

    component.submit();

    const req = httpMock.expectOne(`${environment.apiUrl}/usuarios`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      username: 'nuevo',
      email: 'nuevo@dipalza.cl',
      codigoVendedor: '001',
      tipoVendedor: '0',
      password: 'claveLarga1'
    });
    const result: CrearUsuarioResult = {
      usuario: { id: 1, username: 'nuevo', email: 'nuevo@dipalza.cl', codigoVendedor: '001', tipoVendedor: '0', nombreVendedor: 'Juan Perez', enabled: true, locked: false, createdAt: null },
      correoEnviado: true
    };
    req.flush(result);

    expect(closeSpy).toHaveBeenCalledWith(result);
  });

  it('muestra el mensaje de error del backend si falla la creación', () => {
    component.form.patchValue({ username: 'jperez', password: 'claveLarga1' });

    component.submit();

    const req = httpMock.expectOne(`${environment.apiUrl}/usuarios`);
    req.flush({ message: 'Ya existe un usuario con ese username' }, { status: 400, statusText: 'Bad Request' });

    expect(component.error).toBe('Ya existe un usuario con ese username');
  });

  it('no permite guardar si el formulario es inválido', () => {
    component.submit();
    httpMock.expectNone(`${environment.apiUrl}/usuarios`);
  });
});
```

- [ ] **Step 2: Ejecutar el spec y verificar que falla**

Run: `ng test --include='**/crear-usuario.component.spec.ts'`
Expected: FALLA — el componente no existe.

- [ ] **Step 3: Crear el componente**

Crear `src/app/usuarios/crear-usuario/crear-usuario.component.scss` (vacío).

Crear `src/app/usuarios/crear-usuario/crear-usuario.component.ts`:

```ts
import { Component, OnInit } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ReactiveFormsModule, FormGroup, FormControl, Validators } from '@angular/forms';
import { NgbActiveModal, NgbTypeahead, NgbTypeaheadSelectItemEvent } from '@ng-bootstrap/ng-bootstrap';
import { Observable, OperatorFunction, debounceTime, distinctUntilChanged, map } from 'rxjs';
import { VendedorDTO } from 'app/mapa/models/model';
import { VendedorService } from 'app/mapa/vendedor.service';
import { UsuariosService } from '../usuarios.service';
import { CrearUsuarioPayload } from '../models/model';

@Component({
  selector: 'app-crear-usuario',
  imports: [ReactiveFormsModule, NgbTypeahead],
  templateUrl: './crear-usuario.component.html',
  styleUrl: './crear-usuario.component.scss'
})
export class CrearUsuarioComponent implements OnInit {
  form: FormGroup;
  vendedores: VendedorDTO[] = [];
  vendedorSeleccionado: VendedorDTO | null = null;
  buscadorVendedorControl = new FormControl<string | VendedorDTO | null>('');

  loading = false;
  error = '';

  constructor(
    public activeModal: NgbActiveModal,
    private usuariosService: UsuariosService,
    private vendedorService: VendedorService
  ) {
    this.form = new FormGroup({
      username: new FormControl<string>('', Validators.required),
      email: new FormControl<string>(''),
      password: new FormControl<string>('', [Validators.required, Validators.minLength(8)])
    });
  }

  ngOnInit(): void {
    this.vendedorService.getVendedores().subscribe({
      next: (vendedores) => { this.vendedores = vendedores; },
      error: () => { this.error = 'No se pudo cargar la lista de vendedores.'; }
    });
    this.buscadorVendedorControl.valueChanges.subscribe(v => {
      if (v !== this.vendedorSeleccionado) {
        this.vendedorSeleccionado = null;
      }
    });
  }

  buscarVendedor: OperatorFunction<string, readonly VendedorDTO[]> = (text$: Observable<string>) =>
    text$.pipe(
      debounceTime(200),
      distinctUntilChanged(),
      map(term => {
        const t = term.toLowerCase().trim();
        if (t.length < 2) {
          return [];
        }
        return this.vendedores
          .filter(v => v.codigo.toLowerCase().includes(t) || v.nombre.toLowerCase().includes(t))
          .slice(0, 10);
      })
    );

  formatearVendedor = (v: VendedorDTO): string => v ? `${v.codigo} - ${v.nombre}` : '';

  seleccionarVendedor(event: NgbTypeaheadSelectItemEvent<VendedorDTO>): void {
    this.vendedorSeleccionado = event.item;
  }

  generarClave(): void {
    this.form.get('password')?.setValue(this.generarClaveAleatoria());
    this.form.get('password')?.markAsDirty();
  }

  private generarClaveAleatoria(): string {
    const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%';
    const valores = new Uint32Array(12);
    crypto.getRandomValues(valores);
    let clave = '';
    for (let i = 0; i < valores.length; i++) {
      clave += chars[valores[i] % chars.length];
    }
    return clave;
  }

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.loading = true;
    this.error = '';

    const payload: CrearUsuarioPayload = {
      username: this.form.get('username')?.value,
      email: this.form.get('email')?.value || undefined,
      codigoVendedor: this.vendedorSeleccionado?.codigo,
      tipoVendedor: this.vendedorSeleccionado?.tipo,
      password: this.form.get('password')?.value
    };

    this.usuariosService.crear(payload).subscribe({
      next: (result) => {
        this.loading = false;
        this.activeModal.close(result);
      },
      error: (err: HttpErrorResponse) => {
        this.loading = false;
        this.error = err.status === 400 && err.error?.message
          ? err.error.message
          : 'No se pudo crear el usuario. Intente nuevamente.';
      }
    });
  }
}
```

Crear `src/app/usuarios/crear-usuario/crear-usuario.component.html`:

```html
<div class="modal-header">
  <h5 class="modal-title">Agregar usuario</h5>
  <button type="button" class="btn-close" aria-label="Cerrar" (click)="activeModal.dismiss()"></button>
</div>

<div class="modal-body">
  <form [formGroup]="form" (ngSubmit)="submit()">
    <div class="form-group">
      <label>Username</label>
      <input type="text" formControlName="username" class="form-control" placeholder="Ej: jperez" />
      @if (form.get('username')?.touched && form.get('username')?.invalid) {
        <small style="color:red">El username es obligatorio</small>
      }
    </div>

    <div class="form-group">
      <label>Correo (opcional)</label>
      <input type="email" formControlName="email" class="form-control" placeholder="Ej: jperez@dipalza.cl" />
    </div>

    <div class="form-group">
      <label>Vendedor asociado (opcional, busque por código o nombre)</label>
      <input type="text" class="form-control" [formControl]="buscadorVendedorControl"
        [ngbTypeahead]="buscarVendedor" [resultFormatter]="formatearVendedor"
        [inputFormatter]="formatearVendedor" (selectItem)="seleccionarVendedor($event)"
        placeholder="Ej: 001 o Juan Pérez" />
    </div>

    <div class="form-group">
      <label>Clave inicial</label>
      <div class="d-flex gap-2">
        <input type="text" formControlName="password" class="form-control" placeholder="Mínimo 8 caracteres" />
        <button type="button" class="btn btn-secondary" (click)="generarClave()">Generar clave</button>
      </div>
      @if (form.get('password')?.touched && form.get('password')?.invalid) {
        <small style="color:red">La clave debe tener al menos 8 caracteres</small>
      }
    </div>

    @if (error) {
      <div class="alert alert-danger">{{ error }}</div>
    }
  </form>
</div>

<div class="modal-footer">
  <button type="button" class="btn btn-secondary" (click)="activeModal.dismiss()">Cancelar</button>
  <button type="button" class="btn btn-primary" [disabled]="form.invalid || loading" (click)="submit()">
    @if (loading) {
      <span class="spinner-border spinner-border-sm me-1"></span>
    }
    Guardar
  </button>
</div>
```

- [ ] **Step 4: Ejecutar el spec y verificar que pasa**

Run: `ng test --include='**/crear-usuario.component.spec.ts'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/app/usuarios/crear-usuario
git commit -m "feat: agrega el diálogo para crear usuarios"
```

---

## Task 8: Frontend — diálogo `ModificarUsuarioComponent`

**Files:**
- Create: `src/app/usuarios/modificar-usuario/modificar-usuario.component.ts`
- Create: `src/app/usuarios/modificar-usuario/modificar-usuario.component.html`
- Create: `src/app/usuarios/modificar-usuario/modificar-usuario.component.scss` (vacío)
- Test: `src/app/usuarios/modificar-usuario/modificar-usuario.component.spec.ts`

**Interfaces:**
- Consumes: `UsuariosService.actualizar` (Task 6), `VendedorService`/`VendedorDTO` (igual que Task 7).
- Produces (usado por Task 10): `ModificarUsuarioComponent` (standalone), `@Input() usuario!: Usuario`, cierra con `activeModal.close(usuario: Usuario)` al guardar.

- [ ] **Step 1: Escribir el spec que falla**

Crear `src/app/usuarios/modificar-usuario/modificar-usuario.component.spec.ts`:

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { NgbActiveModal } from '@ng-bootstrap/ng-bootstrap';
import { environment } from 'environments/environment';

import { ModificarUsuarioComponent } from './modificar-usuario.component';
import { VendedorDTO } from 'app/mapa/models/model';
import { Usuario } from '../models/model';

describe('ModificarUsuarioComponent', () => {
  let component: ModificarUsuarioComponent;
  let fixture: ComponentFixture<ModificarUsuarioComponent>;
  let httpMock: HttpTestingController;

  const vendedor: VendedorDTO = { codigo: '001', tipo: '0', nombre: 'Juan Perez' } as VendedorDTO;
  const usuario: Usuario = {
    id: 1, username: 'jperez', email: 'j@dipalza.cl', codigoVendedor: '001', tipoVendedor: '0',
    nombreVendedor: 'Juan Perez', enabled: true, locked: false, createdAt: null
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ModificarUsuarioComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), NgbActiveModal]
    }).compileComponents();

    fixture = TestBed.createComponent(ModificarUsuarioComponent);
    component = fixture.componentInstance;
    component.usuario = usuario;
    httpMock = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    httpMock.expectOne(`${environment.apiUrl}/vendedores`).flush([vendedor]);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should create y precarga el formulario con los datos del usuario', () => {
    expect(component.form.get('email')?.value).toBe('j@dipalza.cl');
    expect(component.form.get('enabled')?.value).toBeTrue();
    expect(component.vendedorSeleccionado).toEqual(vendedor);
  });

  it('quitarVendedor limpia la selección', () => {
    component.quitarVendedor();
    expect(component.vendedorSeleccionado).toBeNull();
  });

  it('guarda los cambios y cierra el modal con el usuario actualizado', () => {
    component.form.patchValue({ enabled: false, locked: true });
    const closeSpy = spyOn(component.activeModal, 'close');

    component.submit();

    const req = httpMock.expectOne(`${environment.apiUrl}/usuarios/1`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({
      email: 'j@dipalza.cl',
      codigoVendedor: '001',
      tipoVendedor: '0',
      enabled: false,
      locked: true
    });
    const actualizado: Usuario = { ...usuario, enabled: false, locked: true };
    req.flush(actualizado);

    expect(closeSpy).toHaveBeenCalledWith(actualizado);
  });

  it('muestra el mensaje de error del backend si falla la actualización', () => {
    component.submit();

    const req = httpMock.expectOne(`${environment.apiUrl}/usuarios/1`);
    req.flush({ message: 'Ya existe un usuario con ese correo' }, { status: 400, statusText: 'Bad Request' });

    expect(component.error).toBe('Ya existe un usuario con ese correo');
  });
});
```

- [ ] **Step 2: Ejecutar el spec y verificar que falla**

Run: `ng test --include='**/modificar-usuario.component.spec.ts'`
Expected: FALLA — el componente no existe.

- [ ] **Step 3: Crear el componente**

Crear `src/app/usuarios/modificar-usuario/modificar-usuario.component.scss` (vacío).

Crear `src/app/usuarios/modificar-usuario/modificar-usuario.component.ts`:

```ts
import { Component, Input, OnInit } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ReactiveFormsModule, FormGroup, FormControl } from '@angular/forms';
import { NgbActiveModal, NgbTypeahead, NgbTypeaheadSelectItemEvent } from '@ng-bootstrap/ng-bootstrap';
import { Observable, OperatorFunction, debounceTime, distinctUntilChanged, map } from 'rxjs';
import { VendedorDTO } from 'app/mapa/models/model';
import { VendedorService } from 'app/mapa/vendedor.service';
import { UsuariosService } from '../usuarios.service';
import { ActualizarUsuarioPayload, Usuario } from '../models/model';

@Component({
  selector: 'app-modificar-usuario',
  imports: [ReactiveFormsModule, NgbTypeahead],
  templateUrl: './modificar-usuario.component.html',
  styleUrl: './modificar-usuario.component.scss'
})
export class ModificarUsuarioComponent implements OnInit {
  @Input() usuario!: Usuario;

  form: FormGroup;
  vendedores: VendedorDTO[] = [];
  vendedorSeleccionado: VendedorDTO | null = null;
  buscadorVendedorControl = new FormControl<string | VendedorDTO | null>('');

  loading = false;
  error = '';

  constructor(
    public activeModal: NgbActiveModal,
    private usuariosService: UsuariosService,
    private vendedorService: VendedorService
  ) {
    this.form = new FormGroup({
      email: new FormControl<string>(''),
      enabled: new FormControl<boolean>(true),
      locked: new FormControl<boolean>(false)
    });
  }

  ngOnInit(): void {
    this.form.patchValue({
      email: this.usuario.email ?? '',
      enabled: this.usuario.enabled,
      locked: this.usuario.locked
    });

    this.vendedorService.getVendedores().subscribe({
      next: (vendedores) => {
        this.vendedores = vendedores;
        if (this.usuario.codigoVendedor && this.usuario.tipoVendedor) {
          const actual = vendedores.find(v =>
            v.codigo === this.usuario.codigoVendedor && v.tipo === this.usuario.tipoVendedor);
          if (actual) {
            this.vendedorSeleccionado = actual;
            this.buscadorVendedorControl.setValue(actual);
          }
        }
      },
      error: () => { this.error = 'No se pudo cargar la lista de vendedores.'; }
    });

    this.buscadorVendedorControl.valueChanges.subscribe(v => {
      if (v !== this.vendedorSeleccionado) {
        this.vendedorSeleccionado = null;
      }
    });
  }

  buscarVendedor: OperatorFunction<string, readonly VendedorDTO[]> = (text$: Observable<string>) =>
    text$.pipe(
      debounceTime(200),
      distinctUntilChanged(),
      map(term => {
        const t = term.toLowerCase().trim();
        if (t.length < 2) {
          return [];
        }
        return this.vendedores
          .filter(v => v.codigo.toLowerCase().includes(t) || v.nombre.toLowerCase().includes(t))
          .slice(0, 10);
      })
    );

  formatearVendedor = (v: VendedorDTO): string => v ? `${v.codigo} - ${v.nombre}` : '';

  seleccionarVendedor(event: NgbTypeaheadSelectItemEvent<VendedorDTO>): void {
    this.vendedorSeleccionado = event.item;
  }

  quitarVendedor(): void {
    this.vendedorSeleccionado = null;
    this.buscadorVendedorControl.setValue('');
  }

  submit(): void {
    this.loading = true;
    this.error = '';

    const payload: ActualizarUsuarioPayload = {
      email: this.form.get('email')?.value || undefined,
      codigoVendedor: this.vendedorSeleccionado?.codigo,
      tipoVendedor: this.vendedorSeleccionado?.tipo,
      enabled: this.form.get('enabled')?.value,
      locked: this.form.get('locked')?.value
    };

    this.usuariosService.actualizar(this.usuario.id, payload).subscribe({
      next: (usuario) => {
        this.loading = false;
        this.activeModal.close(usuario);
      },
      error: (err: HttpErrorResponse) => {
        this.loading = false;
        this.error = err.status === 400 && err.error?.message
          ? err.error.message
          : 'No se pudo modificar el usuario. Intente nuevamente.';
      }
    });
  }
}
```

Crear `src/app/usuarios/modificar-usuario/modificar-usuario.component.html`:

```html
<div class="modal-header">
  <h5 class="modal-title">Modificar usuario</h5>
  <button type="button" class="btn-close" aria-label="Cerrar" (click)="activeModal.dismiss()"></button>
</div>

<div class="modal-body">
  <form [formGroup]="form" (ngSubmit)="submit()">
    <div class="form-group">
      <label>Username</label>
      <input type="text" class="form-control" [value]="usuario.username" disabled />
    </div>

    <div class="form-group">
      <label>Correo</label>
      <input type="email" formControlName="email" class="form-control" placeholder="Ej: jperez@dipalza.cl" />
    </div>

    <div class="form-group">
      <label>Vendedor asociado (opcional, busque por código o nombre)</label>
      <div class="d-flex gap-2">
        <input type="text" class="form-control" [formControl]="buscadorVendedorControl"
          [ngbTypeahead]="buscarVendedor" [resultFormatter]="formatearVendedor"
          [inputFormatter]="formatearVendedor" (selectItem)="seleccionarVendedor($event)"
          placeholder="Ej: 001 o Juan Pérez" />
        <button type="button" class="btn btn-secondary" (click)="quitarVendedor()">Quitar</button>
      </div>
    </div>

    <div class="form-check">
      <input type="checkbox" formControlName="enabled" class="form-check-input" id="enabledCheck" />
      <label class="form-check-label" for="enabledCheck">Habilitado</label>
    </div>

    <div class="form-check">
      <input type="checkbox" formControlName="locked" class="form-check-input" id="lockedCheck" />
      <label class="form-check-label" for="lockedCheck">Bloqueado</label>
    </div>

    @if (error) {
      <div class="alert alert-danger">{{ error }}</div>
    }
  </form>
</div>

<div class="modal-footer">
  <button type="button" class="btn btn-secondary" (click)="activeModal.dismiss()">Cancelar</button>
  <button type="button" class="btn btn-primary" [disabled]="loading" (click)="submit()">
    @if (loading) {
      <span class="spinner-border spinner-border-sm me-1"></span>
    }
    Guardar
  </button>
</div>
```

- [ ] **Step 4: Ejecutar el spec y verificar que pasa**

Run: `ng test --include='**/modificar-usuario.component.spec.ts'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/app/usuarios/modificar-usuario
git commit -m "feat: agrega el diálogo para modificar usuarios"
```

---

## Task 9: Frontend — diálogo `VerUsuarioComponent` y `ListadoUsuariosComponent`

**Files:**
- Create: `src/app/usuarios/ver-usuario/ver-usuario.component.ts`
- Create: `src/app/usuarios/ver-usuario/ver-usuario.component.html`
- Create: `src/app/usuarios/ver-usuario/ver-usuario.component.scss` (vacío)
- Create: `src/app/usuarios/listado-usuarios/listado-usuarios.component.ts`
- Create: `src/app/usuarios/listado-usuarios/listado-usuarios.component.html`
- Create: `src/app/usuarios/listado-usuarios/listado-usuarios.component.scss` (vacío)
- Test: `src/app/usuarios/listado-usuarios/listado-usuarios.component.spec.ts`

**Interfaces:**
- Consumes: `UsuariosService` (Task 6), `CrearUsuarioComponent` (Task 7), `ModificarUsuarioComponent` (Task 8).
- Produces (usado por Task 10): `ListadoUsuariosComponent` (standalone, selector `app-listado-usuarios`).

- [ ] **Step 1: Crear `VerUsuarioComponent` (sin TDD — solo lectura, sin lógica de negocio propia)**

Crear `src/app/usuarios/ver-usuario/ver-usuario.component.scss` (vacío).

Crear `src/app/usuarios/ver-usuario/ver-usuario.component.ts`:

```ts
import { Component, Input } from '@angular/core';
import { NgbActiveModal } from '@ng-bootstrap/ng-bootstrap';
import { Usuario } from '../models/model';

@Component({
  selector: 'app-ver-usuario',
  imports: [],
  templateUrl: './ver-usuario.component.html',
  styleUrl: './ver-usuario.component.scss'
})
export class VerUsuarioComponent {
  @Input() usuario!: Usuario;

  constructor(public activeModal: NgbActiveModal) {}
}
```

Crear `src/app/usuarios/ver-usuario/ver-usuario.component.html`:

```html
<div class="modal-header">
  <h5 class="modal-title">Detalle de usuario</h5>
  <button type="button" class="btn-close" aria-label="Cerrar" (click)="activeModal.dismiss()"></button>
</div>

<div class="modal-body">
  <dl class="row">
    <dt class="col-sm-4">Username</dt>
    <dd class="col-sm-8">{{ usuario.username }}</dd>

    <dt class="col-sm-4">Correo</dt>
    <dd class="col-sm-8">{{ usuario.email || '-' }}</dd>

    <dt class="col-sm-4">Vendedor asociado</dt>
    <dd class="col-sm-8">
      @if (usuario.nombreVendedor) {
        {{ usuario.nombreVendedor }} ({{ usuario.codigoVendedor }}-{{ usuario.tipoVendedor }})
      } @else {
        -
      }
    </dd>

    <dt class="col-sm-4">Estado</dt>
    <dd class="col-sm-8">
      {{ usuario.enabled ? 'Habilitado' : 'Deshabilitado' }} / {{ usuario.locked ? 'Bloqueado' : 'Desbloqueado' }}
    </dd>

    @if (usuario.createdAt) {
      <dt class="col-sm-4">Creado el</dt>
      <dd class="col-sm-8">{{ usuario.createdAt }}</dd>
    }
  </dl>
</div>

<div class="modal-footer">
  <button type="button" class="btn btn-secondary" (click)="activeModal.dismiss()">Cerrar</button>
</div>
```

- [ ] **Step 2: Escribir el spec que falla para `ListadoUsuariosComponent`**

Crear `src/app/usuarios/listado-usuarios/listado-usuarios.component.spec.ts`:

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { NgbModal } from '@ng-bootstrap/ng-bootstrap';
import { of } from 'rxjs';
import { environment } from 'environments/environment';
import Swal from 'sweetalert2';

import { ListadoUsuariosComponent } from './listado-usuarios.component';
import { Usuario } from '../models/model';

describe('ListadoUsuariosComponent', () => {
  let component: ListadoUsuariosComponent;
  let fixture: ComponentFixture<ListadoUsuariosComponent>;
  let httpMock: HttpTestingController;
  let modalSpy: jasmine.SpyObj<NgbModal>;

  const usuario: Usuario = {
    id: 1, username: 'jperez', email: 'j@dipalza.cl', codigoVendedor: null, tipoVendedor: null,
    nombreVendedor: null, enabled: true, locked: false, createdAt: null
  };

  beforeEach(async () => {
    modalSpy = jasmine.createSpyObj('NgbModal', ['open']);
    modalSpy.open.and.returnValue({ componentInstance: {}, closed: of(undefined) } as any);

    await TestBed.configureTestingModule({
      imports: [ListadoUsuariosComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: NgbModal, useValue: modalSpy }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(ListadoUsuariosComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    httpMock.expectOne(`${environment.apiUrl}/usuarios`).flush([usuario]);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should create y carga la lista', () => {
    expect(component.rows).toEqual([usuario]);
  });

  it('toggleHabilitado confirma y llama deshabilitar si el usuario está habilitado', () => {
    spyOn(Swal, 'fire').and.resolveTo({ isConfirmed: true } as any);

    component.toggleHabilitado(usuario);

    return fixture.whenStable().then(() => {
      const req = httpMock.expectOne(`${environment.apiUrl}/usuarios/1/deshabilitar`);
      expect(req.request.method).toBe('PATCH');
      req.flush({ ...usuario, enabled: false });

      httpMock.expectOne(`${environment.apiUrl}/usuarios`).flush([{ ...usuario, enabled: false }]);
    });
  });

  it('toggleBloqueado no llama al backend si el usuario cancela la confirmación', () => {
    spyOn(Swal, 'fire').and.resolveTo({ isConfirmed: false } as any);

    component.toggleBloqueado(usuario);

    return fixture.whenStable().then(() => {
      httpMock.expectNone(`${environment.apiUrl}/usuarios/1/bloquear`);
    });
  });
});
```

- [ ] **Step 3: Ejecutar el spec y verificar que falla**

Run: `ng test --include='**/listado-usuarios.component.spec.ts'`
Expected: FALLA — el componente no existe.

- [ ] **Step 4: Crear `ListadoUsuariosComponent`**

Crear `src/app/usuarios/listado-usuarios/listado-usuarios.component.scss` (vacío).

Crear `src/app/usuarios/listado-usuarios/listado-usuarios.component.ts`:

```ts
import { Component, DestroyRef, inject, OnInit } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgxDatatableModule } from '@swimlane/ngx-datatable';
import { NgbModal } from '@ng-bootstrap/ng-bootstrap';
import { RouterLink } from '@angular/router';
import Swal from 'sweetalert2';
import { UsuariosService } from '../usuarios.service';
import { Usuario } from '../models/model';
import { VerUsuarioComponent } from '../ver-usuario/ver-usuario.component';
import { CrearUsuarioComponent } from '../crear-usuario/crear-usuario.component';
import { ModificarUsuarioComponent } from '../modificar-usuario/modificar-usuario.component';

@Component({
  selector: 'app-listado-usuarios',
  imports: [NgxDatatableModule, RouterLink],
  templateUrl: './listado-usuarios.component.html',
  styleUrl: './listado-usuarios.component.scss'
})
export class ListadoUsuariosComponent implements OnInit {
  loadingIndicator = true;
  reorderable = true;
  scrollBarHorizontal = window.innerWidth < 1200;
  error = '';

  rows: Usuario[] = [];
  temp: Usuario[] = [];

  private usuariosService = inject(UsuariosService);
  private destroyRef = inject(DestroyRef);
  private modalService = inject(NgbModal);

  ngOnInit(): void {
    this.cargar();
  }

  cargar(): void {
    this.loadingIndicator = true;
    this.usuariosService.listar()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (usuarios) => {
          this.rows = usuarios;
          this.temp = usuarios;
          this.loadingIndicator = false;
        },
        error: (error: HttpErrorResponse) => {
          this.error = 'No se pudo cargar la lista de usuarios.';
          this.loadingIndicator = false;
        }
      });
  }

  updateFilter(event: Event): void {
    const input = event.target as HTMLInputElement;
    const val = input.value.toLowerCase();
    this.rows = this.temp.filter((u: Usuario) =>
      u.username.toLowerCase().indexOf(val) !== -1 ||
      (u.email ?? '').toLowerCase().indexOf(val) !== -1 || !val);
  }

  agregar(): void {
    const modalRef = this.modalService.open(CrearUsuarioComponent);
    modalRef.closed.subscribe(() => this.cargar());
  }

  ver(row: Usuario): void {
    const modalRef = this.modalService.open(VerUsuarioComponent);
    modalRef.componentInstance.usuario = row;
  }

  modificar(row: Usuario): void {
    const modalRef = this.modalService.open(ModificarUsuarioComponent);
    modalRef.componentInstance.usuario = row;
    modalRef.closed.subscribe(() => this.cargar());
  }

  toggleHabilitado(row: Usuario): void {
    Swal.fire({
      title: row.enabled ? 'Deshabilitar usuario' : 'Habilitar usuario',
      text: `¿${row.enabled ? 'Deshabilitar' : 'Habilitar'} a ${row.username}?`,
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
      const peticion = row.enabled ? this.usuariosService.deshabilitar(row.id) : this.usuariosService.habilitar(row.id);
      peticion.subscribe({
        next: () => this.cargar(),
        error: () => { this.error = 'No se pudo actualizar el estado del usuario. Intente nuevamente.'; }
      });
    });
  }

  toggleBloqueado(row: Usuario): void {
    Swal.fire({
      title: row.locked ? 'Desbloquear usuario' : 'Bloquear usuario',
      text: `¿${row.locked ? 'Desbloquear' : 'Bloquear'} a ${row.username}?`,
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
      const peticion = row.locked ? this.usuariosService.desbloquear(row.id) : this.usuariosService.bloquear(row.id);
      peticion.subscribe({
        next: () => this.cargar(),
        error: () => { this.error = 'No se pudo actualizar el bloqueo del usuario. Intente nuevamente.'; }
      });
    });
  }
}
```

Crear `src/app/usuarios/listado-usuarios/listado-usuarios.component.html`:

```html
<section class="main-content">
    <ul class="breadcrumb breadcrumb-style">
        <li class="breadcrumb-item">
            <h5 class="page-title m-b-0">Gestionar Usuarios</h5>
        </li>
        <li class="breadcrumb-item bcrumb-1">
            <a routerLink="/" class="d-flex align-items-center">
                <i class="fas fa-home font-20"></i>
            </a>
        </li>
    </ul>
    <div class="section-body">
        @if (error) {
            <div class="alert alert-danger">{{ error }}</div>
        }
        <div class="row">
            <div class="col-sm-12">
                <div class="form-group row">
                    <div class="col-sm-4">
                        <div class="d-flex">
                            <label class="col-form-label msr-3">Buscar:</label>
                            <input type="text" class="form-control" (keyup)='updateFilter($event)'>
                        </div>
                    </div>
                    <div class="col-sm-auto ms-auto">
                        <button class="btn btn-primary" (click)="agregar()">Agregar</button>
                    </div>
                </div>
                <ngx-datatable class="material" [rows]="rows" [loadingIndicator]="loadingIndicator"
                    columnMode="force" [headerHeight]="50" [footerHeight]="50" rowHeight="auto" [limit]="10"
                    [scrollbarH]="scrollBarHorizontal" [reorderable]="reorderable">
                    <ngx-datatable-column name="Username" headerClass="align-center-header" prop="username">
                        <ng-template let-row="row" ngx-datatable-cell-template>
                            <div>{{ row.username }}</div>
                        </ng-template>
                    </ngx-datatable-column>
                    <ngx-datatable-column name="Correo" headerClass="align-center-header" prop="email">
                        <ng-template let-row="row" ngx-datatable-cell-template>
                            <div>{{ row.email || '-' }}</div>
                        </ng-template>
                    </ngx-datatable-column>
                    <ngx-datatable-column name="Vendedor" headerClass="align-center-header" prop="nombreVendedor">
                        <ng-template let-row="row" ngx-datatable-cell-template>
                            <div>{{ row.nombreVendedor || '-' }}</div>
                        </ng-template>
                    </ngx-datatable-column>
                    <ngx-datatable-column name="Estado" headerClass="align-center-header" cellClass="align-center-cell">
                        <ng-template let-row="row" ngx-datatable-cell-template>
                            @if (!row.enabled) {
                                <span class="badge bg-secondary">Deshabilitado</span>
                            } @else if (row.locked) {
                                <span class="badge bg-danger">Bloqueado</span>
                            } @else {
                                <span class="badge bg-success">Activo</span>
                            }
                        </ng-template>
                    </ngx-datatable-column>
                    <ngx-datatable-column name="" [width]="160" [sortable]="false" headerClass="align-center-header" cellClass="align-center-cell">
                        <ng-template let-row="row" ngx-datatable-cell-template>
                            <a class="msr-2 h-auto tblViewBtn" (click)="ver(row)" style="cursor: pointer;" title="Ver">
                                <i class="fas fa-eye"></i>
                            </a>
                            <a class="msr-2 h-auto tblEditBtn" (click)="modificar(row)" style="cursor: pointer;" title="Modificar">
                                <i class="fas fa-pencil-alt"></i>
                            </a>
                            <a class="msr-2 h-auto tblEditBtn" (click)="toggleHabilitado(row)" style="cursor: pointer;"
                                [title]="row.enabled ? 'Deshabilitar' : 'Habilitar'">
                                <i class="fas" [class.fa-toggle-on]="row.enabled" [class.fa-toggle-off]="!row.enabled"></i>
                            </a>
                            <a class="msr-2 h-auto tblDelBtn" (click)="toggleBloqueado(row)" style="cursor: pointer;"
                                [title]="row.locked ? 'Desbloquear' : 'Bloquear'">
                                <i class="fas" [class.fa-lock]="row.locked" [class.fa-lock-open]="!row.locked"></i>
                            </a>
                        </ng-template>
                    </ngx-datatable-column>
                </ngx-datatable>
            </div>
        </div>
    </div>
</section>
```

- [ ] **Step 5: Ejecutar el spec y verificar que pasa**

Run: `ng test --include='**/listado-usuarios.component.spec.ts'`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add src/app/usuarios/ver-usuario src/app/usuarios/listado-usuarios
git commit -m "feat: agrega el listado de usuarios y el diálogo de ver detalle"
```

---

## Task 10: Frontend — ruta, menú lateral y filtro por rol

**Files:**
- Create: `src/app/usuarios/usuarios.routes.ts`
- Modify: `src/app/app.routes.ts`
- Modify: `src/assets/data/routes.json`
- Modify: `src/app/layout/sidebar/sidebar.component.ts`
- Test: `src/app/layout/sidebar/sidebar.component.spec.ts` (nuevo)

**Interfaces:**
- Consumes: `ListadoUsuariosComponent` (Task 9), `AdminGuard` (Task 5).
- Produces: ruta `/usuarios` protegida, ítem de menú visible solo para admins.

- [ ] **Step 1: Crear `usuarios.routes.ts`**

Crear `src/app/usuarios/usuarios.routes.ts`:

```ts
import { Route } from '@angular/router';

export const USUARIOS_ROUTES: Route[] = [
    {
        path: '',
        loadComponent: () => import('./listado-usuarios/listado-usuarios.component').then((m) => m.ListadoUsuariosComponent)
    }
];
```

- [ ] **Step 2: Registrar la ruta en `app.routes.ts`**

Agregar el import junto a `AuthGuard`:

```ts
import { AdminGuard } from './core/guard/admin.guard';
```

Y agregar el hijo nuevo dentro del arreglo `children` de `MainLayoutComponent`, junto a `numerados`:

```ts
            {
                path: 'usuarios',
                canActivate: [AdminGuard],
                loadChildren: () =>
                    import('./usuarios/usuarios.routes').then((m) => m.USUARIOS_ROUTES)
            }
```

- [ ] **Step 3: Agregar la entrada en `routes.json`**

Agregar al final del arreglo `routes` (después de la entrada
`"path": "numerados"`, separada por coma):

```json
    ,
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

- [ ] **Step 4: Escribir el spec que falla para el filtro del sidebar**

Crear `src/app/layout/sidebar/sidebar.component.spec.ts`:

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from '@core';
import { SidebarComponent } from './sidebar.component';
import { RouteInfo } from './sidebar.metadata';

describe('SidebarComponent', () => {
  let component: SidebarComponent;
  let fixture: ComponentFixture<SidebarComponent>;
  let httpMock: HttpTestingController;
  let authServiceSpy: jasmine.SpyObj<Partial<AuthService>>;

  const routes: RouteInfo[] = [
    { path: 'ventas', title: 'Ventas', iconType: 'feather', icon: 'home', class: '', groupTitle: false, badge: '', badgeClass: '', submenu: [] },
    { path: 'usuarios', title: 'Gestionar Usuarios', iconType: 'feather', icon: 'users', class: '', groupTitle: false, badge: '', badgeClass: '', submenu: [] }
  ];

  function setup(isAdmin: boolean) {
    authServiceSpy = {
      isAdmin: jasmine.createSpy('isAdmin').and.returnValue(isAdmin),
      currentUserValue: { username: 'jperez' } as any
    };

    TestBed.configureTestingModule({
      imports: [SidebarComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: authServiceSpy }
      ]
    });

    fixture = TestBed.createComponent(SidebarComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    httpMock = TestBed.inject(HttpTestingController);
    httpMock.expectOne('assets/data/routes.json').flush({ routes });
  }

  afterEach(() => {
    httpMock.verify();
  });

  it('incluye "Gestionar Usuarios" cuando el usuario es admin', () => {
    setup(true);
    expect(component.sidebarItems.some(r => r.path === 'usuarios')).toBeTrue();
  });

  it('excluye "Gestionar Usuarios" cuando el usuario no es admin', () => {
    setup(false);
    expect(component.sidebarItems.some(r => r.path === 'usuarios')).toBeFalse();
    expect(component.sidebarItems.some(r => r.path === 'ventas')).toBeTrue();
  });
});
```

- [ ] **Step 5: Ejecutar el spec y verificar que falla**

Run: `ng test --include='**/sidebar.component.spec.ts'`
Expected: FALLA — hoy el filtro no excluye "usuarios" para no-admins (el
primer test debería pasar ya, el segundo debe fallar).

- [ ] **Step 6: Filtrar el ítem en `sidebar.component.ts`**

En `ngOnInit()`, cambiar:

```ts
        this.sidebarItems = routes.filter((sidebarItem) => sidebarItem);
```

por:

```ts
        this.sidebarItems = routes.filter((sidebarItem) =>
          sidebarItem && (sidebarItem.path !== 'usuarios' || this.authService.isAdmin()));
```

- [ ] **Step 7: Ejecutar el spec y verificar que pasa**

Run: `ng test --include='**/sidebar.component.spec.ts'`
Expected: PASS (2 tests).

- [ ] **Step 8: Verificar compilación y correr la suite completa**

Run: `npx tsc -p tsconfig.app.json --noEmit`
Expected: sin errores.

Run: `ng test --watch=false`
Expected: sin regresiones frente al conteo previo a esta rama (ver Task
10 del plan de gestión de numerados para el último baseline conocido;
confirmar el número exacto de fallos preexistentes antes de comparar,
puede haber cambiado).

- [ ] **Step 9: Commit**

```bash
git add src/app/usuarios/usuarios.routes.ts \
        src/app/app.routes.ts \
        src/assets/data/routes.json \
        src/app/layout/sidebar/sidebar.component.ts \
        src/app/layout/sidebar/sidebar.component.spec.ts
git commit -m "feat: agrega la ruta y el ítem de menú de Gestionar Usuarios, visible solo para admins"
```

---

## Self-Review (completado durante la escritura del plan)

**Cobertura del spec:**
- Restricción a `ROLE_ADMIN` solo en web_client → Task 1 (backend, regla de URL) + Task 5/10 (frontend, guard + menú).
- CRUD completo de usuarios (crear/ver/modificar/listar) → Tasks 2-4 (backend) + Tasks 6-9 (frontend).
- Habilitar/deshabilitar, bloquear/desbloquear (4 íconos de fila + toggles en modificar) → Task 3/4 (backend) + Task 8/9 (frontend).
- Vendedor opcional, mostrado de solo lectura, reasignable/removible → Task 3 (`resolverVendedor`) + Task 7/8 (typeahead reutilizando `VendedorService` existente).
- `username` fijo tras la creación → Task 3 (`ActualizarUsuarioDTO` no lo incluye) + Task 8 (input disabled en el formulario).
- Clave inicial generada o escrita por el admin, correo best-effort → Task 2 (`EmailService`) + Task 3 (`crear`) + Task 7 (botón "Generar clave").
- Revocar tokens al restringir acceso (hallazgo del brainstorming, no en el spec original pero derivado de él) → Task 3 (`actualizar`, `deshabilitar`, `bloquear`).

**Placeholders:** ninguno — todos los pasos incluyen código completo.

**Consistencia de tipos:** `UsuarioDTO`/`CrearUsuarioDTO`/`CrearUsuarioResultDTO`/`ActualizarUsuarioDTO`
(backend, Task 2) y `Usuario`/`CrearUsuarioPayload`/`CrearUsuarioResult`/`ActualizarUsuarioPayload`
(frontend, Task 6) tienen los mismos campos con los mismos nombres en
ambos lados. Los métodos de `UsuariosService` (Task 6) se usan con la
misma firma en los componentes de las Tasks 7-9. `VendedorDTO`/`VendedorService`
reutilizados tal cual existen hoy en `app/mapa/`, sin duplicar.

**Riesgo identificado y mitigado:** sin este plan, deshabilitar o
bloquear a un usuario con sesión activa no lo habría echado
efectivamente (ni `refresh` ni `webrefresh` chequean `enabled`/`locked`)
— se corrige revocando refresh tokens en los mismos puntos donde ya se
hace tras un cambio de clave.
