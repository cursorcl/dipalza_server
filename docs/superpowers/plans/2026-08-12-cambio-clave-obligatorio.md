# Cambio de clave obligatorio (clave temporal) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Marcar `AppUser.mustChangePassword` al crear una cuenta o al usar "olvidé mi clave" (rediseñado para generar y enviar una clave temporal, sin código de 6 dígitos), exponerlo en las respuestas de login, y forzar un diálogo bloqueante de cambio de clave (solo pide la clave nueva) en `dipalza_mobile` y `dipalza_web_client`, cerrando sesión al completarlo.

**Architecture:** Backend: nuevo campo en `AppUser` + rediseño de `AuthController.forgotPassword` (genera clave temporal en vez de código, elimina `PasswordResetToken`/`/auth/reset-password`) + `EmailService` con botón condicional a `ROLE_ADMIN`. Mobile: nueva pantalla bloqueante que reutiliza `CambiarClaveBloc` sin mostrar el campo de clave actual (se reenvía la clave con la que el usuario acaba de loguearse). Web: mismo patrón vía un `NgbModal` no descartable.

**Tech Stack:** Spring Boot 3.5 / JPA (backend), Flutter/Dart con RxDart BLoCs (mobile), Angular standalone components + `@ng-bootstrap/ng-bootstrap` (web). Testing: JUnit 5 + Mockito + AssertJ + MockMvc (backend), `flutter_test` (mobile), Karma/Jasmine (web).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-12-cambio-clave-obligatorio-design.md` (repo `dipalza_server`).
- Se elimina por completo el código de 6 dígitos: entidad `PasswordResetToken`, `PasswordResetTokenRepo`, tabla `dbo.app_password_reset_token`, endpoint `POST /auth/reset-password` (backend), y sus consumidores en `dipalza_mobile` (`RecuperarClaveProvider.restablecerClave`, `ResetClaveBloc`) y `dipalza_web_client` (`AuthService.resetPassword`, `ResetComponent`, ruta `/authentication/reset`).
- Aplica a todos los roles, incluido admin — no hay excepción por rol en la lógica de `mustChangePassword`.
- El correo con la clave temporal (creación o "olvidé mi clave") solo incluye el botón "Cambiar mi clave" si el destinatario tiene `ROLE_ADMIN`; para el resto, solo texto con la clave.
- El diálogo/pantalla de cambio forzado (ambas apps) es bloqueante — sin botón de cerrar, sin omitir — pide solo la clave nueva y su confirmación. La clave actual/temporal se reenvía automáticamente al backend usando la que el usuario acaba de escribir para iniciar sesión, sin pedírsela de nuevo.
- Al completar el cambio forzado: se cierra la sesión (mobile: `borrarCredenciales()` + vuelta a login; web: `logout()` + redirección a `/authentication/signin`) y se exige iniciar sesión de nuevo.
- La migración de base de datos real se aplica **antes** de desplegar el jar nuevo (práctica ya establecida en este proyecto — ver `base_de_datos/procedimiento_actualizar.md`).
- Antes de cada tarea, verificar rama activa: se trabaja en `feat/gestion-usuarios` en los 3 repos (`dipalza_server`, `dipalza_mobile`, `dipalza_web_client`).

---

## Task 1: Backend — modelo de datos (`mustChangePassword`)

**Files:**
- Modify: `dipalza/src/main/java/cl/eos/dipalza/entity/AppUser.java`
- Create: `base_de_datos/archive/migration/migration_20260812.sql`
- Modify: `base_de_datos/deploy_desde_cero/01_esquema_ventas.sql`

**Interfaces:**
- Produces (usado por Tasks 2-3): `AppUser.isMustChangePassword(): boolean`, `AppUser.setMustChangePassword(boolean): void`.

Cambio aditivo — no rompe nada existente, no requiere test dedicado (campo booleano simple, mismo criterio que `enabled`/`locked`).

- [ ] **Step 1: Agregar el campo a `AppUser`**

En `dipalza/src/main/java/cl/eos/dipalza/entity/AppUser.java`, agregar junto a `locked`:

```java
	private boolean enabled = true;
	private boolean locked = false;
	private boolean mustChangePassword = false;
```

Y su getter/setter, junto a los de `locked`:

```java
	public boolean isLocked() {
		return locked;
	}

	public void setLocked(boolean locked) {
		this.locked = locked;
	}

	public boolean isMustChangePassword() {
		return mustChangePassword;
	}

	public void setMustChangePassword(boolean mustChangePassword) {
		this.mustChangePassword = mustChangePassword;
	}
```

- [ ] **Step 2: Crear la migración para la BD real**

Crear `base_de_datos/archive/migration/migration_20260812.sql`:

```sql
-- Agrega el flag de cambio de clave obligatorio y elimina la
-- infraestructura del codigo de 6 digitos (reemplazada por clave
-- temporal enviada por correo -- ver AuthController.forgotPassword).

SET QUOTED_IDENTIFIER ON;
GO

BEGIN TRAN;

ALTER TABLE dbo.app_user
    ADD must_change_password bit NOT NULL DEFAULT 0;
GO

DROP TABLE dbo.app_password_reset_token;

COMMIT TRAN;
```

- [ ] **Step 3: Actualizar el esquema de instalación desde cero**

En `base_de_datos/deploy_desde_cero/01_esquema_ventas.sql`, en la definición de `dbo.app_user` (línea ~20-33), agregar la columna:

```sql
CREATE TABLE dbo.app_user (
    id         bigint IDENTITY(1,1) NOT NULL,
    username   varchar(100) COLLATE Modern_Spanish_CI_AS NOT NULL,
    password   varchar(100) COLLATE Modern_Spanish_CI_AS NOT NULL,
    enabled    bit  NOT NULL DEFAULT 1,
    locked     bit  NOT NULL DEFAULT 0,
    must_change_password bit NOT NULL DEFAULT 0,
    created_at date NOT NULL DEFAULT CONVERT(date, SYSUTCDATETIME()),
    updated_at date NOT NULL DEFAULT CONVERT(date, SYSUTCDATETIME()),
    codigo_vendedor varchar(3) COLLATE Modern_Spanish_CI_AS NULL,  -- [DRIFT] existe en producción pero no estaba en install_dipalza_sync.sql; vincula la cuenta de login a un vendedor
    tipo_vendedor   varchar(1) COLLATE Modern_Spanish_CI_AS NULL,  -- [DRIFT] idem
    email           varchar(255) COLLATE Modern_Spanish_CI_AS NULL,  -- agregada en migration_20260808.sql, para recuperación de clave por correo
    CONSTRAINT PK_app_user PRIMARY KEY (id),
    CONSTRAINT UQ_app_user_username UNIQUE (username)
);
GO
```

Y eliminar por completo el bloque `CREATE TABLE dbo.app_password_reset_token` (más abajo en el mismo archivo, línea ~202-211), incluida la sentencia `GO` que lo sigue si queda huérfana.

- [ ] **Step 4: Compilar**

Run: `cd dipalza && mvn compile -Dfrontend.skip=true`
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add dipalza/src/main/java/cl/eos/dipalza/entity/AppUser.java \
        base_de_datos/archive/migration/migration_20260812.sql \
        base_de_datos/deploy_desde_cero/01_esquema_ventas.sql
git commit -m "feat: agrega AppUser.mustChangePassword y su migración"
```

---

## Task 2: Backend — `EmailService` y `AuthController`: clave temporal en vez de código

**Files:**
- Modify: `dipalza/src/main/java/cl/eos/dipalza/service/EmailService.java`
- Modify: `dipalza/src/main/java/cl/eos/dipalza/controller/AuthController.java`
- Modify: `dipalza/src/main/java/cl/eos/dipalza/service/UsuarioAdminService.java` (solo la línea que llama a `enviarCredencialesIniciales`, para que compile con la nueva firma — el resto de esta clase lo toca la Task 3)
- Delete: `dipalza/src/main/java/cl/eos/dipalza/entity/PasswordResetToken.java`
- Delete: `dipalza/src/main/java/cl/eos/dipalza/repository/PasswordResetTokenRepo.java`
- Modify: `dipalza/src/test/java/cl/eos/dipalza/service/EmailServiceTest.java`
- Modify: `dipalza/src/test/java/cl/eos/dipalza/controller/PasswordFlowControllerTest.java`

**Interfaces:**
- Consumes: `AppUser.isMustChangePassword()`/`setMustChangePassword()` (Task 1), `RefreshTokenService.revocarTokensDeUsuario` (ya existente), `AppRole.getName()` (ya existente, usado igual que en `JwtService`).
- Produces (usado por Tasks 3-4 y por mobile/web):
  - `EmailService.enviarCredencialesIniciales(String destinatario, String username, String claveInicial, boolean esAdmin): void` (firma cambiada — antes 3 params)
  - `EmailService.enviarClaveTemporalPorOlvido(String destinatario, String username, String claveTemporal, boolean esAdmin): void` (nuevo)
  - `TokenResponse(String accessToken, String refreshToken, long expiresInSeconds, VendedorDTO vendedor, boolean mustChangePassword)` — antes sin el último campo.
  - `WebLoginRes(String token, String refreshToken, long expiresInSeconds, long id, String username, String firstName, String lastName, boolean mustChangePassword)` — antes sin el último campo.
  - `POST /auth/forgot-password` ya no genera código: genera clave temporal, la deja como clave de la cuenta, marca `mustChangePassword=true`, revoca tokens, envía correo.
  - `POST /auth/reset-password` **eliminado**.

- [ ] **Step 1: Reescribir `EmailService`**

Reemplazar el contenido completo de `dipalza/src/main/java/cl/eos/dipalza/service/EmailService.java`:

```java
package cl.eos.dipalza.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

// Solo se usa desde AuthController (olvidé mi clave) y UsuarioAdminService
// (credenciales iniciales), ambos ya restringidos a estos perfiles; evita
// exigir configuración SMTP en dev-nosec/it, donde no se levantan esos
// controllers/servicios.
@Service
@Profile({ "dev-sec", "prod-sec" })
public class EmailService {

	@Autowired
	private JavaMailSender mailSender;

	@Value("${app.frontend-base-url:http://localhost:4200}")
	private String frontendBaseUrl;

	public void enviarCredencialesIniciales(String destinatario, String username, String claveInicial, boolean esAdmin) {
		String cuerpo = """
				<p>Se creó una cuenta de Dipalza para ti.</p>
				<p><strong>Usuario:</strong> %s<br/><strong>Clave inicial:</strong> %s</p>
				<p>Deberás cambiarla la primera vez que inicies sesión.</p>
				""".formatted(username, claveInicial);
		enviar(destinatario, "Dipalza - Tu cuenta fue creada", cuerpo, esAdmin);
	}

	public void enviarClaveTemporalPorOlvido(String destinatario, String username, String claveTemporal, boolean esAdmin) {
		String cuerpo = """
				<p>Restablecimos tu clave de Dipalza a pedido tuyo.</p>
				<p><strong>Usuario:</strong> %s<br/><strong>Clave temporal:</strong> %s</p>
				<p>Deberás cambiarla la próxima vez que inicies sesión. Si no solicitaste este cambio, contacta al administrador.</p>
				""".formatted(username, claveTemporal);
		enviar(destinatario, "Dipalza - Tu clave fue restablecida", cuerpo, esAdmin);
	}

	private void enviar(String destinatario, String asunto, String cuerpoHtml, boolean esAdmin) {
		String textoBoton = esAdmin ? "Cambiar mi clave" : null;
		String urlBoton = esAdmin ? frontendBaseUrl + "/#/perfil" : null;
		String html = construirHtml(cuerpoHtml, textoBoton, urlBoton);
		try {
			MimeMessage mimeMessage = mailSender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, "UTF-8");
			helper.setTo(destinatario);
			helper.setSubject(asunto);
			helper.setText(html, true);
			mailSender.send(mimeMessage);
		} catch (MessagingException e) {
			throw new MailPreparationException("No se pudo preparar el correo: " + asunto, e);
		}
	}

	private String construirHtml(String cuerpoHtml, String textoBoton, String urlBoton) {
		String logoUrl = frontendBaseUrl + "/assets/images/logo_dipalza.png";
		String boton = (textoBoton == null) ? "" : """
				<tr>
					<td align="center" style="padding:24px 0;">
						<a href="%s" style="background-color:#1b6ec2;color:#ffffff;text-decoration:none;
							padding:12px 28px;border-radius:4px;font-family:Arial,sans-serif;font-size:14px;
							display:inline-block;">%s</a>
					</td>
				</tr>
				""".formatted(urlBoton, textoBoton);
		return """
				<!DOCTYPE html>
				<html>
				<body style="margin:0;padding:0;background-color:#f4f4f4;">
					<table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color:#f4f4f4;padding:24px 0;">
						<tr>
							<td align="center">
								<table role="presentation" width="480" cellpadding="0" cellspacing="0"
									style="background-color:#ffffff;border-radius:8px;overflow:hidden;font-family:Arial,sans-serif;color:#333333;">
									<tr>
										<td align="center" style="padding:24px 0;background-color:#ffffff;">
											<img src="%s" alt="Dipalza" style="max-height:60px;" />
										</td>
									</tr>
									<tr>
										<td style="padding:8px 32px 24px 32px;font-size:14px;line-height:1.5;">
											%s
										</td>
									</tr>
									%s
								</table>
							</td>
						</tr>
					</table>
				</body>
				</html>
				""".formatted(logoUrl, cuerpoHtml, boton);
	}
}
```

(Nota: `enviarCodigoRecuperacionClave` queda eliminado — no aparece en el reemplazo de arriba.)

- [ ] **Step 2: Actualizar `EmailServiceTest`**

Reemplazar el contenido completo de `dipalza/src/test/java/cl/eos/dipalza/service/EmailServiceTest.java`:

```java
package cl.eos.dipalza.service;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock JavaMailSender mailSender;
    @InjectMocks EmailService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "frontendBaseUrl", "http://localhost:4200");
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
    }

    @Test
    void enviarCredencialesIniciales_noAdmin_sinBoton() throws Exception {
        service.enviarCredencialesIniciales("nuevo@dipalza.cl", "jperez", "Cl4ve!Segura", false);

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());

        MimeMessage mensaje = captor.getValue();
        assertThat(mensaje.getAllRecipients()[0].toString()).isEqualTo("nuevo@dipalza.cl");
        assertThat(mensaje.getSubject()).isEqualTo("Dipalza - Tu cuenta fue creada");
        String contenido = (String) mensaje.getContent();
        assertThat(contenido)
                .contains("jperez")
                .contains("Cl4ve!Segura")
                .contains("http://localhost:4200/assets/images/logo_dipalza.png")
                .doesNotContain("Cambiar mi clave");
    }

    @Test
    void enviarCredencialesIniciales_admin_conBoton() throws Exception {
        service.enviarCredencialesIniciales("admin@dipalza.cl", "admin1", "Cl4ve!Segura", true);

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());

        String contenido = (String) captor.getValue().getContent();
        assertThat(contenido)
                .contains("Cambiar mi clave")
                .contains("http://localhost:4200/#/perfil");
    }

    @Test
    void enviarClaveTemporalPorOlvido_noAdmin_sinBoton() throws Exception {
        service.enviarClaveTemporalPorOlvido("nuevo@dipalza.cl", "jperez", "Tmp123456789", false);

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());

        MimeMessage mensaje = captor.getValue();
        assertThat(mensaje.getSubject()).isEqualTo("Dipalza - Tu clave fue restablecida");
        String contenido = (String) mensaje.getContent();
        assertThat(contenido)
                .contains("Tmp123456789")
                .doesNotContain("Cambiar mi clave");
    }

    @Test
    void enviarClaveTemporalPorOlvido_admin_conBoton() throws Exception {
        service.enviarClaveTemporalPorOlvido("admin@dipalza.cl", "admin1", "Tmp123456789", true);

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());

        String contenido = (String) captor.getValue().getContent();
        assertThat(contenido).contains("Cambiar mi clave").contains("http://localhost:4200/#/perfil");
    }
}
```

- [ ] **Step 3: Ejecutar `EmailServiceTest` y verificar que pasa**

Run: `cd dipalza && mvn test -Dtest=EmailServiceTest`
Expected: PASS (4 tests). (Fallará primero por compilación mientras `EmailService` no tenga la nueva firma — normal, ya se implementó en el Step 1; si se sigue el plan en orden esto ya debe pasar.)

- [ ] **Step 4: Eliminar `PasswordResetToken` y su repositorio**

```bash
rm dipalza/src/main/java/cl/eos/dipalza/entity/PasswordResetToken.java
rm dipalza/src/main/java/cl/eos/dipalza/repository/PasswordResetTokenRepo.java
```

- [ ] **Step 5: Reescribir `AuthController`**

En `dipalza/src/main/java/cl/eos/dipalza/controller/AuthController.java`:

Quitar los imports de `PasswordResetToken`/`PasswordResetTokenRepo`:

```java
import cl.eos.dipalza.entity.PasswordResetToken;
```
```java
import cl.eos.dipalza.repository.PasswordResetTokenRepo;
```

Quitar la constante `RESET_TOKEN_MINUTOS`:

```java
	private static final long RESET_TOKEN_MINUTOS = 30;
```

Quitar el campo `resetTokenRepo` y su parámetro/asignación en el constructor:

```java
	private final PasswordResetTokenRepo resetTokenRepo;
```

```java
	public AuthController(UserRepo users, VendedorRepository vendedorRepo, PasswordEncoder enc, JwtService jwt,
			RefreshTokenRepo rtRepo, PasswordResetTokenRepo resetTokenRepo, EmailService emailService,
			RefreshTokenService refreshTokenService) {
		this.users = users;
		this.enc = enc;
		this.jwt = jwt;
		this.refreshTokenRepo = rtRepo;
		this.vendedorRepo = vendedorRepo;
		this.resetTokenRepo = resetTokenRepo;
		this.emailService = emailService;
		this.refreshTokenService = refreshTokenService;
	}
```

reemplaza por:

```java
	public AuthController(UserRepo users, VendedorRepository vendedorRepo, PasswordEncoder enc, JwtService jwt,
			RefreshTokenRepo rtRepo, EmailService emailService, RefreshTokenService refreshTokenService) {
		this.users = users;
		this.enc = enc;
		this.jwt = jwt;
		this.refreshTokenRepo = rtRepo;
		this.vendedorRepo = vendedorRepo;
		this.emailService = emailService;
		this.refreshTokenService = refreshTokenService;
	}
```

Los records `TokenResponse`/`WebLoginRes` agregan `mustChangePassword`:

```java
	public record TokenResponse(String accessToken, String refreshToken, long expiresInSeconds, VendedorDTO vendedor) {
	}
```

reemplaza por:

```java
	public record TokenResponse(String accessToken, String refreshToken, long expiresInSeconds, VendedorDTO vendedor,
			boolean mustChangePassword) {
	}
```

```java
	public record WebLoginRes(String token, String refreshToken, long expiresInSeconds, long id, String username, String firstName, String lastName ) {
	}
```

reemplaza por:

```java
	public record WebLoginRes(String token, String refreshToken, long expiresInSeconds, long id, String username,
			String firstName, String lastName, boolean mustChangePassword) {
	}
```

`generateTokenRes(...)` pasa el flag:

```java
		return new TokenResponse(access, refreshJwt, refreshHr * 60L * 60L, vendedorDTO);
```

reemplaza por:

```java
		return new TokenResponse(access, refreshJwt, refreshHr * 60L * 60L, vendedorDTO, u.isMustChangePassword());
```

`weblogin()` y `webRefresh()` pasan el flag (dos ocurrencias del mismo patrón, una por método):

```java
		return new WebLoginRes(access, refreshRaw, 60L * 10, id, userName, firstName, lastName); // 10 min si así configuraste
```

(dentro de `weblogin()`) reemplaza por:

```java
		return new WebLoginRes(access, refreshRaw, 60L * 10, id, userName, firstName, lastName, u.isMustChangePassword());
```

y (dentro de `webRefresh()`) reemplaza por:

```java
		return new WebLoginRes(access, newRefreshRaw, 60L * 10, id, userName, firstName, lastName, u.isMustChangePassword());
```

El record `ResetPasswordReq` y el endpoint `resetPassword(...)` se eliminan por completo:

```java
	public record ResetPasswordReq(String username, String codigo, String claveNueva) {
	}
```

```java
	@PostMapping("/reset-password")
	public void resetPassword(@RequestBody ResetPasswordReq req) {
		var u = users.findByUsername(req.username())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Código inválido o vencido"));

		var token = resetTokenRepo.findByTokenHashAndUsedFalse(hashToken(req.codigo()))
				.filter(t -> t.getUser().getId().equals(u.getId()))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Código inválido o vencido"));

		if (!token.getExpiresAt().isAfter(Instant.now()))
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Código inválido o vencido");

		if (req.claveNueva() == null || req.claveNueva().length() < CLAVE_LARGO_MINIMO)
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"La clave nueva debe tener al menos " + CLAVE_LARGO_MINIMO + " caracteres");

		token.setUsed(true);
		resetTokenRepo.save(token);

		u.setPassword(enc.encode(req.claveNueva()));
		users.save(u);

		refreshTokenService.revocarTokensDeUsuario(u);
	}
```

`forgotPassword(...)` se reescribe:

```java
	@PostMapping("/forgot-password")
	public void forgotPassword(@RequestBody ForgotPasswordReq req) {
		String clave = req.usernameOrEmail() == null ? "" : req.usernameOrEmail().trim();

		Optional<AppUser> userOpt = clave.contains("@") ? users.findByEmail(clave) : users.findByUsername(clave);

		// Responde siempre igual, exista o no el usuario/correo, para no filtrar
		// qué cuentas están registradas.
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

(`hashToken(...)` **no se toca** — lo sigue usando `generateTokenRes(...)` para el refresh token del login móvil.)

- [ ] **Step 6: Arreglar la compilación de `UsuarioAdminService`**

En `dipalza/src/main/java/cl/eos/dipalza/service/UsuarioAdminService.java`, dentro de `crear(...)`, cambiar únicamente la llamada a `emailService.enviarCredencialesIniciales`:

```java
        boolean correoEnviado = false;
        if (email != null) {
            try {
                emailService.enviarCredencialesIniciales(email, u.getUsername(), req.password());
                correoEnviado = true;
            } catch (RuntimeException e) {
                correoEnviado = false;
            }
        }
```

reemplaza por:

```java
        boolean correoEnviado = false;
        if (email != null) {
            try {
                boolean esAdmin = u.getRoles().stream().anyMatch(r -> "ROLE_ADMIN".equals(r.getName()));
                emailService.enviarCredencialesIniciales(email, u.getUsername(), req.password(), esAdmin);
                correoEnviado = true;
            } catch (RuntimeException e) {
                correoEnviado = false;
            }
        }
```

(No se toca nada más de este archivo — `mustChangePassword` se agrega en la Task 3.)

- [ ] **Step 7: Actualizar los 3 tests de `UsuarioAdminServiceTest` que llaman a `enviarCredencialesIniciales` con la firma vieja**

En `dipalza/src/test/java/cl/eos/dipalza/service/UsuarioAdminServiceTest.java`:

```java
        verify(emailService, never()).enviarCredencialesIniciales(anyString(), anyString(), anyString());
```

reemplaza por:

```java
        verify(emailService, never()).enviarCredencialesIniciales(anyString(), anyString(), anyString(), anyBoolean());
```

```java
        verify(emailService).enviarCredencialesIniciales("nuevo@dipalza.cl", "nuevo", "claveLarga1");
```

reemplaza por:

```java
        verify(emailService).enviarCredencialesIniciales("nuevo@dipalza.cl", "nuevo", "claveLarga1", false);
```

```java
        doThrow(new RuntimeException("SMTP down")).when(emailService)
                .enviarCredencialesIniciales(anyString(), anyString(), anyString());
```

reemplaza por:

```java
        doThrow(new RuntimeException("SMTP down")).when(emailService)
                .enviarCredencialesIniciales(anyString(), anyString(), anyString(), anyBoolean());
```

Agregar el import estático que falte:

```java
import static org.mockito.ArgumentMatchers.anyBoolean;
```

- [ ] **Step 8: Reescribir los tests de "olvidé mi clave" en `PasswordFlowControllerTest`**

En `dipalza/src/test/java/cl/eos/dipalza/controller/PasswordFlowControllerTest.java`, el test `forgotPassword_yResetPassword_flujoCompletoActualizaLaClave` y el test `resetPassword_codigoInvalido_retorna400` se eliminan. En su lugar:

```java
	@Test
	void forgotPassword_generaClaveTemporalYMarcaMustChangePassword() throws Exception {
		AppUser creado = crearUsuario("recupera1", "claveOriginal1", "recupera1@test.cl");
		String tokenPrevio = jwt.generateAccess(creado);
		RefreshToken rt = new RefreshToken();
		rt.setUser(creado);
		rt.setTokenHash("hash-previo-recupera1");
		rt.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
		refreshTokenRepo.save(rt);

		mockMvc.perform(post("/auth/forgot-password")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(Map.of("usernameOrEmail", "recupera1@test.cl"))))
				.andExpect(status().isOk());

		ArgumentCaptor<String> claveCaptor = ArgumentCaptor.forClass(String.class);
		verify(emailService).enviarClaveTemporalPorOlvido(org.mockito.ArgumentMatchers.eq("recupera1@test.cl"),
				org.mockito.ArgumentMatchers.eq("recupera1"), claveCaptor.capture(), org.mockito.ArgumentMatchers.eq(false));
		String claveTemporal = claveCaptor.getValue();

		AppUser actualizado = userRepo.findByUsername("recupera1").orElseThrow();
		assertThat(enc.matches(claveTemporal, actualizado.getPassword())).isTrue();
		assertThat(actualizado.isMustChangePassword()).isTrue();
		assertThat(refreshTokenRepo.findAll().stream().filter(t -> t.getUser().getId().equals(creado.getId()))).isEmpty();
	}
```

Y en `forgotPassword_reintentoInmediato_seThrotlleaYNoReenviaCorreo`, cambiar la verificación final:

```java
		verify(emailService).enviarCodigoRecuperacionClave(anyString(), anyString());
```

reemplaza por:

```java
		verify(emailService).enviarClaveTemporalPorOlvido(anyString(), anyString(), anyString(), anyBoolean());
```

(`forgotPassword_correoNoRegistrado_noEnviaCorreoYRespondeOk` no cambia.)

Agregar el import que falte si no está: `import static org.mockito.ArgumentMatchers.anyBoolean;`.

- [ ] **Step 9: Agregar cobertura para `mustChangePassword` en el login**

Al final de la clase, antes del cierre, agregar:

```java
	@Test
	void login_usuarioConMustChangePassword_loRetornaEnLaRespuesta() throws Exception {
		AppUser u = crearUsuario("debecambiar1", "claveTemp1", null);
		u.setMustChangePassword(true);
		userRepo.save(u);

		mockMvc.perform(post("/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(Map.of("username", "debecambiar1", "password", "claveTemp1"))))
				.andExpect(status().isNotFound());
	}
```

Espera — `crearUsuario(...)` no asigna `Vendedor`, y `/auth/login` hace `vendedorRepo.findById(u.getVendedor().getId())`, lo que lanzaría NPE si `vendedor` es null. Revisar: los tests existentes de este archivo NUNCA llaman a `/auth/login` (solo a `/auth/weblogin` indirectamente vía JWT directo, o a los endpoints de `/api/usuario` y `/auth/forgot-password`/`reset-password`). Para no depender de un `Vendedor` real de la BD H2 (que no existe en este test de contexto liviano), verificar el flag vía **`/auth/weblogin`** en su lugar, que sí es alcanzable sin `Vendedor` (de hecho lo exige nulo):

Reemplazar el test de arriba por:

```java
	@Test
	void weblogin_usuarioConMustChangePassword_loRetornaEnLaRespuesta() throws Exception {
		AppUser u = crearUsuario("debecambiar1", "claveTemp1", null);
		u.setMustChangePassword(true);
		userRepo.save(u);

		mockMvc.perform(post("/auth/weblogin")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(Map.of("username", "debecambiar1", "password", "claveTemp1"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mustChangePassword", is(true)));
	}
```

Agregar los imports estáticos que falten:

```java
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
```

- [ ] **Step 10: Ejecutar toda la suite de backend**

Run: `cd dipalza && mvn test -Dfrontend.skip=true`
Expected: `BUILD SUCCESS`, sin errores de compilación ni tests fallando.

- [ ] **Step 11: Commit**

```bash
git add dipalza/src/main/java/cl/eos/dipalza/service/EmailService.java \
        dipalza/src/main/java/cl/eos/dipalza/controller/AuthController.java \
        dipalza/src/main/java/cl/eos/dipalza/service/UsuarioAdminService.java \
        dipalza/src/test/java/cl/eos/dipalza/service/EmailServiceTest.java \
        dipalza/src/test/java/cl/eos/dipalza/service/UsuarioAdminServiceTest.java \
        dipalza/src/test/java/cl/eos/dipalza/controller/PasswordFlowControllerTest.java
git rm dipalza/src/main/java/cl/eos/dipalza/entity/PasswordResetToken.java \
       dipalza/src/main/java/cl/eos/dipalza/repository/PasswordResetTokenRepo.java
git commit -m "feat: reemplaza el código de recuperación por clave temporal enviada por correo"
```

---

## Task 3: Backend — `UsuarioAdminService.crear()` y `UsuarioController.cambiarClave()`

**Files:**
- Modify: `dipalza/src/main/java/cl/eos/dipalza/service/UsuarioAdminService.java`
- Modify: `dipalza/src/main/java/cl/eos/dipalza/controller/UsuarioController.java`
- Modify: `dipalza/src/test/java/cl/eos/dipalza/service/UsuarioAdminServiceTest.java`
- Modify: `dipalza/src/test/java/cl/eos/dipalza/controller/PasswordFlowControllerTest.java`

**Interfaces:**
- Consumes: `AppUser.setMustChangePassword` (Task 1).
- Produces: `crear()` deja `mustChangePassword=true`; `cambiarClave()` lo limpia a `false`.

- [ ] **Step 1: Escribir el test que falla para `crear()`**

En `UsuarioAdminServiceTest.java`, agregar (junto a `crear_exitoso_encriptaPasswordYGuarda`):

```java
    @Test
    void crear_dejaMustChangePasswordEnTrue() {
        when(userRepo.findByUsername("nuevo")).thenReturn(Optional.empty());
        when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        service.crear(new CrearUsuarioDTO("nuevo", null, null, null, "claveLarga1"));

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepo).save(captor.capture());
        assertThat(captor.getValue().isMustChangePassword()).isTrue();
    }
```

- [ ] **Step 2: Ejecutar y verificar que falla**

Run: `cd dipalza && mvn test -Dtest=UsuarioAdminServiceTest#crear_dejaMustChangePasswordEnTrue`
Expected: FALLA — `isMustChangePassword()` devuelve `false`.

- [ ] **Step 3: Implementar en `UsuarioAdminService.crear()`**

```java
        u.setEnabled(true);
        u.setLocked(false);
        u.setVendedor(vendedor);
```

reemplaza por:

```java
        u.setEnabled(true);
        u.setLocked(false);
        u.setMustChangePassword(true);
        u.setVendedor(vendedor);
```

- [ ] **Step 4: Ejecutar y verificar que pasa**

Run: `cd dipalza && mvn test -Dtest=UsuarioAdminServiceTest`
Expected: PASS (22 tests — 21 previos + este nuevo).

- [ ] **Step 5: Escribir el test que falla para `cambiarClave()`**

En `PasswordFlowControllerTest.java`, agregar:

```java
	@Test
	void cambiarClave_datosValidos_limpiaMustChangePassword() throws Exception {
		AppUser u = crearUsuario("cambio4", "claveVieja4", null);
		u.setMustChangePassword(true);
		userRepo.save(u);
		String token = jwt.generateAccess(u);

		mockMvc.perform(put("/api/usuario/cambiar-clave")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								Map.of("claveActual", "claveVieja4", "claveNueva", "claveNueva4"))))
				.andExpect(status().isOk());

		AppUser actualizado = userRepo.findByUsername("cambio4").orElseThrow();
		assertThat(actualizado.isMustChangePassword()).isFalse();
	}
```

- [ ] **Step 6: Ejecutar y verificar que falla**

Run: `cd dipalza && mvn test -Dtest=PasswordFlowControllerTest#cambiarClave_datosValidos_limpiaMustChangePassword`
Expected: FALLA — el flag sigue en `true`.

- [ ] **Step 7: Implementar en `UsuarioController.cambiarClave()`**

```java
		u.setPassword(enc.encode(req.claveNueva()));
		users.save(u);

		refreshTokenService.revocarTokensDeUsuario(u);
```

reemplaza por:

```java
		u.setPassword(enc.encode(req.claveNueva()));
		u.setMustChangePassword(false);
		users.save(u);

		refreshTokenService.revocarTokensDeUsuario(u);
```

- [ ] **Step 8: Ejecutar toda la suite de backend**

Run: `cd dipalza && mvn test -Dfrontend.skip=true`
Expected: `BUILD SUCCESS`.

- [ ] **Step 9: Commit**

```bash
git add dipalza/src/main/java/cl/eos/dipalza/service/UsuarioAdminService.java \
        dipalza/src/main/java/cl/eos/dipalza/controller/UsuarioController.java \
        dipalza/src/test/java/cl/eos/dipalza/service/UsuarioAdminServiceTest.java \
        dipalza/src/test/java/cl/eos/dipalza/controller/PasswordFlowControllerTest.java
git commit -m "feat: marca mustChangePassword al crear un usuario y lo limpia al cambiar la clave"
```

---

## Task 4: Mobile — `LoginResponseModel`

**Files:**
- Modify: `lib/src/model/login_response_model.dart`
- Create: `test/unit/login_response_model_test.dart`

**Interfaces:**
- Produces (usado por Tasks 6-7): `LoginResponseModel.mustChangePassword: bool`.

- [ ] **Step 1: Escribir el test que falla**

Crear `test/unit/login_response_model_test.dart`:

```dart
import 'package:dipalza_movil/src/model/login_response_model.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('fromJson parsea mustChangePassword en true', () {
    final model = LoginResponseModel.fromJson({
      'accessToken': 'a',
      'refreshToken': 'r',
      'expiresInSeconds': 600,
      'mustChangePassword': true,
      'vendedor': {'codigo': '001', 'tipo': '0', 'rut': '11111111-1', 'nombre': 'Juan Perez'},
    });

    expect(model.mustChangePassword, isTrue);
  });

  test('fromJson por defecto deja mustChangePassword en false si no viene', () {
    final model = LoginResponseModel.fromJson({
      'accessToken': 'a',
      'refreshToken': 'r',
      'expiresInSeconds': 600,
      'vendedor': {'codigo': '001', 'tipo': '0', 'rut': '11111111-1', 'nombre': 'Juan Perez'},
    });

    expect(model.mustChangePassword, isFalse);
  });
}
```

- [ ] **Step 2: Ejecutar y verificar que falla**

Run: `flutter test test/unit/login_response_model_test.dart`
Expected: FALLA — el getter `mustChangePassword` no existe.

- [ ] **Step 3: Implementar**

En `lib/src/model/login_response_model.dart`:

```dart
class LoginResponseModel {
  final String accessToken;
  final String refreshToken;
  final int expiresInSeconds;
  final String codigo;
  final String tipo;
  final String rut;
  final String nombre;

  LoginResponseModel({
    required this.accessToken,
    required this.refreshToken,
    required this.expiresInSeconds,
    required this.codigo,
    required this.tipo,
    required this.rut,
    required this.nombre,
  });
```

reemplaza por:

```dart
class LoginResponseModel {
  final String accessToken;
  final String refreshToken;
  final int expiresInSeconds;
  final String codigo;
  final String tipo;
  final String rut;
  final String nombre;
  final bool mustChangePassword;

  LoginResponseModel({
    required this.accessToken,
    required this.refreshToken,
    required this.expiresInSeconds,
    required this.codigo,
    required this.tipo,
    required this.rut,
    required this.nombre,
    this.mustChangePassword = false,
  });
```

```dart
  factory LoginResponseModel.fromJson(Map<String, dynamic> json) {
    final vendedor = json["vendedor"] ?? {};
    return LoginResponseModel(
      accessToken: json["accessToken"],
      refreshToken: json["refreshToken"],
      expiresInSeconds: json["expiresInSeconds"],
      codigo: vendedor["codigo"],
      tipo: vendedor["tipo"],
      rut: vendedor["rut"],
      nombre: vendedor["nombre"],
    );
  }
```

reemplaza por:

```dart
  factory LoginResponseModel.fromJson(Map<String, dynamic> json) {
    final vendedor = json["vendedor"] ?? {};
    return LoginResponseModel(
      accessToken: json["accessToken"],
      refreshToken: json["refreshToken"],
      expiresInSeconds: json["expiresInSeconds"],
      codigo: vendedor["codigo"],
      tipo: vendedor["tipo"],
      rut: vendedor["rut"],
      nombre: vendedor["nombre"],
      mustChangePassword: json["mustChangePassword"] ?? false,
    );
  }
```

Y en `toJson()`:

```dart
  Map<String, dynamic> toJson() => {
        "accessToken": accessToken,
        "refreshToken": refreshToken,
        "expiresInSeconds": expiresInSeconds,
        "vendedor": {
          "codigo": codigo,
          "tipo": tipo,
          "rut": rut,
          "nombre": nombre,
        },
      };
```

reemplaza por:

```dart
  Map<String, dynamic> toJson() => {
        "accessToken": accessToken,
        "refreshToken": refreshToken,
        "expiresInSeconds": expiresInSeconds,
        "vendedor": {
          "codigo": codigo,
          "tipo": tipo,
          "rut": rut,
          "nombre": nombre,
        },
        "mustChangePassword": mustChangePassword,
      };
```

- [ ] **Step 4: Ejecutar y verificar que pasa**

Run: `flutter test test/unit/login_response_model_test.dart`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add lib/src/model/login_response_model.dart test/unit/login_response_model_test.dart
git commit -m "feat: agrega mustChangePassword a LoginResponseModel"
```

---

## Task 5: Mobile — simplificar "olvidé mi clave" a un solo paso

**Files:**
- Modify: `lib/src/page/login/olvide_clave.page.dart`
- Modify: `lib/src/provider/recuperar_clave_provider.dart`
- Delete: `lib/src/bloc/reset_clave_bloc.dart`
- Delete: `test/unit/reset_clave_bloc_test.dart`

**Interfaces:**
- Consumes: nada nuevo (usa `POST /auth/forgot-password`, ya existente, sin cambio de contrato desde el punto de vista del cliente — solo cambia qué hace el backend detrás).
- Produces: `RecuperarClaveProvider.solicitarRecuperacion(String usernameOrEmail): Future<RespuestaModel>` (antes `solicitarCodigo`, renombrado por claridad ya que ya no hay código).

Esta tarea no lleva TDD propio: es simplificación/eliminación de UI y de un método de provider sin lógica de validación propia que testear (mismo criterio que otras pantallas de solo formulario simple en este proyecto). Se verifica con `flutter analyze` + `flutter test` completo (sin regresiones).

- [ ] **Step 1: Simplificar `RecuperarClaveProvider`**

Reemplazar el contenido completo de `lib/src/provider/recuperar_clave_provider.dart`:

```dart
import 'package:dio/dio.dart';
import 'package:dipalza_movil/src/model/respuesta_model.dart';
import 'package:dipalza_movil/src/share/prefs_usuario.dart';

// Llamada sin sesión (usuario aún no está logueado), por eso usa un Dio
// propio en vez de ApiClient().dio -- mismo patrón que VenderdorProvider.
class RecuperarClaveProvider {
  final _dio = Dio(BaseOptions(
    connectTimeout: const Duration(seconds: 10),
    receiveTimeout: const Duration(seconds: 15),
  ));
  final _prefs = PreferenciasUsuario();

  Future<RespuestaModel> solicitarRecuperacion(String usernameOrEmail) async {
    try {
      final resp = await _dio.post(
        '${_prefs.urlBase}/auth/forgot-password',
        data: {'usernameOrEmail': usernameOrEmail},
        options: Options(contentType: Headers.jsonContentType),
      );
      return RespuestaModel(status: resp.statusCode ?? 200, detalle: const {});
    } on DioException catch (e) {
      return RespuestaModel(
        status: e.response?.statusCode ?? 500,
        detalle: {"error": "No se pudo enviar la solicitud. Intente nuevamente."},
      );
    } catch (error) {
      return RespuestaModel(
        status: 500,
        detalle: {"error": "Error en la conexión con el servidor."},
      );
    }
  }
}
```

- [ ] **Step 2: Simplificar `olvide_clave.page.dart` a un solo paso**

Reemplazar el contenido completo de `lib/src/page/login/olvide_clave.page.dart`:

```dart
import 'package:dipalza_movil/src/provider/recuperar_clave_provider.dart';
import 'package:dipalza_movil/src/utils/alert_util.dart' as alertUtil;
import 'package:dipalza_movil/src/utils/utils.dart';
import 'package:flutter/material.dart';

import '../../share/app.navigator.dart';

class OlvideClavePage extends StatefulWidget {
  const OlvideClavePage({super.key});

  @override
  State<OlvideClavePage> createState() => _OlvideClavePageState();
}

class _OlvideClavePageState extends State<OlvideClavePage> {
  final _recuperarClaveProvider = RecuperarClaveProvider();
  final _usuarioOEmailController = TextEditingController();

  bool _isLoading = false;

  @override
  void dispose() {
    _usuarioOEmailController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: colorRojoBase(),
        title: const Text('Recuperar contraseña'),
        centerTitle: true,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(20.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text(
              'Ingrese su usuario o correo registrado. Si existe una cuenta '
              'asociada, le enviaremos una clave temporal por correo — '
              'deberá cambiarla al iniciar sesión con ella.',
            ),
            const SizedBox(height: 20.0),
            TextField(
              controller: _usuarioOEmailController,
              enabled: !_isLoading,
              decoration: InputDecoration(
                prefixIcon: Icon(Icons.person_outline, color: colorRojoBase()),
                labelText: 'Usuario o correo',
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(10)),
              ),
              onChanged: (_) => setState(() {}),
            ),
            const SizedBox(height: 30.0),
            ElevatedButton(
              style: ElevatedButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 15.0),
                backgroundColor: colorRojoBase(),
                foregroundColor: Colors.white,
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
              ),
              onPressed: (_usuarioOEmailController.text.trim().isEmpty || _isLoading)
                  ? null
                  : _solicitarRecuperacion,
              child: _isLoading
                  ? const SizedBox(
                      height: 22,
                      width: 22,
                      child: CircularProgressIndicator(color: Colors.white, strokeWidth: 3),
                    )
                  : const Text('Enviar'),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _solicitarRecuperacion() async {
    FocusScope.of(context).unfocus();
    setState(() => _isLoading = true);

    final resp = await _recuperarClaveProvider
        .solicitarRecuperacion(_usuarioOEmailController.text.trim());

    if (!mounted) return;
    setState(() => _isLoading = false);

    if (resp.status == 200) {
      await alertUtil.showAlertDialog(
          context,
          'Si la cuenta existe, revisa tu correo: te enviamos una clave temporal.',
          Icons.check_circle_outline);
      if (mounted) AppNavigator.pop();
    } else {
      final mensaje = resp.detalle['error']?.toString() ??
          'No se pudo enviar la solicitud. Intente nuevamente.';
      alertUtil.showAlertDialog(context, mensaje, Icons.error_outline);
    }
  }
}
```

- [ ] **Step 3: Eliminar `ResetClaveBloc` y su test**

```bash
rm lib/src/bloc/reset_clave_bloc.dart
rm test/unit/reset_clave_bloc_test.dart
```

Revisar `lib/main.dart`: quitar el `Provider<ResetClaveBloc>`/`ChangeNotifierProvider`/entrada equivalente del `MultiProvider` y su llamada `dispose()`, si existe (buscar `ResetClaveBloc` en `main.dart`).

- [ ] **Step 4: Analizar y correr toda la suite**

Run: `flutter analyze`
Expected: sin errores (puede haber warnings preexistentes, no nuevos relacionados a este cambio).

Run: `flutter test`
Expected: sin regresiones frente al conteo previo a esta rama.

- [ ] **Step 5: Commit**

```bash
git add lib/src/page/login/olvide_clave.page.dart \
        lib/src/provider/recuperar_clave_provider.dart \
        lib/main.dart
git rm lib/src/bloc/reset_clave_bloc.dart test/unit/reset_clave_bloc_test.dart
git commit -m "feat: simplifica 'olvidé mi clave' a un solo paso (clave temporal por correo)"
```

---

## Task 6: Mobile — pantalla de cambio de clave obligatorio

**Files:**
- Create: `lib/src/page/login/cambiar_clave_obligatorio.page.dart`
- Modify: `lib/src/share/app_routes.dart`
- Modify: `lib/src/share/app_router.dart`
- Create: `test/widget/cambiar_clave_obligatorio_page_test.dart`

**Interfaces:**
- Consumes: `CambiarClaveBloc` (ya existente, sin cambios — `lib/src/bloc/cambiar_clave_bloc.dart`), `UsuarioProvider.cambiarClave(String, String)` (ya existente), `PreferenciasUsuario.borrarCredenciales()` (ya existente), `AppNavigator.goToLogin()` (ya existente).
- Produces (usado por Task 7): ruta `AppRoutes.cambiarClaveObligatorio`, que espera `arguments: {'claveActual': String}`.

- [ ] **Step 1: Agregar la ruta**

En `lib/src/share/app_routes.dart`, agregar junto a `cambiarClave`:

```dart
  static const String cambiarClave = 'cambiarClave'; // CambiarClavePage
```

agregar después:

```dart
  static const String cambiarClave = 'cambiarClave'; // CambiarClavePage
  static const String cambiarClaveObligatorio =
      'cambiarClaveObligatorio'; // CambiarClaveObligatorioPage
```

- [ ] **Step 2: Escribir el test que falla**

Crear `test/widget/cambiar_clave_obligatorio_page_test.dart`:

```dart
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:dipalza_movil/src/bloc/cambiar_clave_bloc.dart';
import 'package:dipalza_movil/src/page/login/cambiar_clave_obligatorio.page.dart';

void main() {
  Widget createWidgetUnderTest() {
    return MultiProvider(
      providers: [
        Provider<CambiarClaveBloc>(
          create: (_) => CambiarClaveBloc(),
          dispose: (_, bloc) => bloc.dispose(),
        ),
      ],
      child: const MaterialApp(
        home: CambiarClaveObligatorioPage(claveActual: 'ClaveTemp123'),
      ),
    );
  }

  testWidgets('no muestra el campo de clave actual', (tester) async {
    await tester.pumpWidget(createWidgetUnderTest());

    expect(find.text('Clave actual'), findsNothing);
    expect(find.text('Clave nueva'), findsOneWidget);
    expect(find.text('Confirmar clave nueva'), findsOneWidget);
  });

  testWidgets('no muestra botón de retroceso en el AppBar', (tester) async {
    await tester.pumpWidget(createWidgetUnderTest());

    expect(find.byType(BackButton), findsNothing);
  });
}
```

- [ ] **Step 3: Ejecutar y verificar que falla**

Run: `flutter test test/widget/cambiar_clave_obligatorio_page_test.dart`
Expected: FALLA — el archivo `cambiar_clave_obligatorio.page.dart` no existe.

- [ ] **Step 4: Crear `CambiarClaveObligatorioPage`**

Crear `lib/src/page/login/cambiar_clave_obligatorio.page.dart`:

```dart
import 'package:dipalza_movil/src/bloc/cambiar_clave_bloc.dart';
import 'package:dipalza_movil/src/provider/usuario_provider.dart';
import 'package:dipalza_movil/src/share/app.navigator.dart';
import 'package:dipalza_movil/src/share/prefs_usuario.dart';
import 'package:dipalza_movil/src/utils/alert_util.dart' as alertUtil;
import 'package:dipalza_movil/src/utils/utils.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

class CambiarClaveObligatorioPage extends StatefulWidget {
  final String claveActual;
  const CambiarClaveObligatorioPage({super.key, required this.claveActual});

  @override
  State<CambiarClaveObligatorioPage> createState() =>
      _CambiarClaveObligatorioPageState();
}

class _CambiarClaveObligatorioPageState
    extends State<CambiarClaveObligatorioPage> {
  final _usuarioProvider = UsuarioProvider();
  final _prefs = PreferenciasUsuario();
  bool _isLoading = false;
  bool _obscureNueva = true;
  bool _obscureConfirmar = true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        final bloc = context.read<CambiarClaveBloc>();
        bloc.changeClaveActual(widget.claveActual);
        bloc.changeClaveNueva('');
        bloc.changeConfirmarClave('');
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final bloc = context.read<CambiarClaveBloc>();
    return PopScope(
      canPop: false,
      child: Scaffold(
        appBar: AppBar(
          automaticallyImplyLeading: false,
          backgroundColor: colorRojoBase(),
          title: const Text('Debes cambiar tu clave'),
          centerTitle: true,
        ),
        body: SingleChildScrollView(
          padding: const EdgeInsets.all(20.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text(
                'Por seguridad, debes elegir una clave nueva antes de continuar.',
              ),
              const SizedBox(height: 20.0),
              _campoClaveNueva(bloc),
              const SizedBox(height: 16.0),
              _campoConfirmarClave(bloc),
              const SizedBox(height: 30.0),
              _botonGuardar(bloc),
            ],
          ),
        ),
      ),
    );
  }

  Widget _campoClaveNueva(CambiarClaveBloc bloc) {
    return StreamBuilder<String>(
      stream: bloc.claveNuevaStream,
      builder: (context, snapshot) {
        return TextField(
          obscureText: _obscureNueva,
          enabled: !_isLoading,
          decoration: InputDecoration(
            prefixIcon: Icon(Icons.lock_reset, color: colorRojoBase()),
            labelText: 'Clave nueva',
            errorText: snapshot.hasError ? snapshot.error.toString() : null,
            border: OutlineInputBorder(borderRadius: BorderRadius.circular(10)),
            suffixIcon: IconButton(
              icon: Icon(_obscureNueva ? Icons.visibility : Icons.visibility_off),
              onPressed: () => setState(() => _obscureNueva = !_obscureNueva),
            ),
          ),
          onChanged: bloc.changeClaveNueva,
        );
      },
    );
  }

  Widget _campoConfirmarClave(CambiarClaveBloc bloc) {
    return StreamBuilder<String>(
      stream: bloc.confirmarClaveStream,
      builder: (context, snapshot) {
        return TextField(
          obscureText: _obscureConfirmar,
          enabled: !_isLoading,
          decoration: InputDecoration(
            prefixIcon: Icon(Icons.lock_reset, color: colorRojoBase()),
            labelText: 'Confirmar clave nueva',
            errorText: snapshot.hasError ? snapshot.error.toString() : null,
            border: OutlineInputBorder(borderRadius: BorderRadius.circular(10)),
            suffixIcon: IconButton(
              icon: Icon(_obscureConfirmar ? Icons.visibility : Icons.visibility_off),
              onPressed: () => setState(() => _obscureConfirmar = !_obscureConfirmar),
            ),
          ),
          onChanged: bloc.changeConfirmarClave,
        );
      },
    );
  }

  Widget _botonGuardar(CambiarClaveBloc bloc) {
    return StreamBuilder<bool>(
      stream: bloc.formValidStream,
      builder: (context, snapshot) {
        return ElevatedButton(
          style: ElevatedButton.styleFrom(
            padding: const EdgeInsets.symmetric(vertical: 15.0),
            backgroundColor: colorRojoBase(),
            foregroundColor: Colors.white,
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
          ),
          onPressed: (snapshot.hasData && !_isLoading) ? () => _guardar(bloc) : null,
          child: _isLoading
              ? const SizedBox(
                  height: 22,
                  width: 22,
                  child: CircularProgressIndicator(color: Colors.white, strokeWidth: 3),
                )
              : const Text('Guardar'),
        );
      },
    );
  }

  Future<void> _guardar(CambiarClaveBloc bloc) async {
    FocusScope.of(context).unfocus();
    setState(() => _isLoading = true);

    final resp = await _usuarioProvider.cambiarClave(widget.claveActual, bloc.claveNueva);

    if (!mounted) return;
    setState(() => _isLoading = false);

    if (resp.status == 200) {
      await _prefs.borrarCredenciales();
      if (mounted) AppNavigator.goToLogin();
    } else {
      final mensaje =
          resp.detalle['error']?.toString() ?? 'No se pudo cambiar la clave. Intente nuevamente.';
      alertUtil.showAlertDialog(context, mensaje, Icons.error_outline);
    }
  }
}
```

- [ ] **Step 5: Registrar la ruta en el router**

En `lib/src/share/app_router.dart`, agregar el import:

```dart
import '../page/login/cambiar_clave_obligatorio.page.dart';
```

Y el case, junto a `AppRoutes.olvideClave`:

```dart
      case AppRoutes.olvideClave:
        return MaterialPageRoute(builder: (_) => const OlvideClavePage());
```

agregar después:

```dart
      case AppRoutes.cambiarClaveObligatorio:
        if (args is Map<String, dynamic> && args['claveActual'] is String) {
          return MaterialPageRoute(
              builder: (_) => CambiarClaveObligatorioPage(claveActual: args['claveActual']));
        }
        return _errorRoute("Faltan argumentos en Cambiar Clave Obligatorio");
```

- [ ] **Step 6: Ejecutar el test y verificar que pasa**

Run: `flutter test test/widget/cambiar_clave_obligatorio_page_test.dart`
Expected: PASS (2 tests).

- [ ] **Step 7: Commit**

```bash
git add lib/src/page/login/cambiar_clave_obligatorio.page.dart \
        lib/src/share/app_routes.dart \
        lib/src/share/app_router.dart \
        test/widget/cambiar_clave_obligatorio_page_test.dart
git commit -m "feat: agrega la pantalla bloqueante de cambio de clave obligatorio"
```

---

## Task 7: Mobile — `login.page.dart` detecta `mustChangePassword`

**Files:**
- Modify: `lib/src/page/login/login.page.dart`

**Interfaces:**
- Consumes: `LoginResponseModel.mustChangePassword` (Task 4), ruta `AppRoutes.cambiarClaveObligatorio` (Task 6).

Sin TDD propio: `_login()` no tiene cobertura de test hoy (`test/widget/login_page_test.dart` solo verifica renderizado estático, no ejercita esta lógica — limitación ya existente en este proyecto, no introducida por este cambio). Se verifica con `flutter analyze` + revisión manual del flujo.

- [ ] **Step 1: Insertar el chequeo en `_login()`**

En `lib/src/page/login/login.page.dart`, dentro de `_login(...)`:

```dart
    if (resp.status == 200 && mounted) {
      LoginResponseModel response = LoginResponseModel.fromJson(resp.detalle);
      prefs.vendedor = response.codigo;
      prefs.name = response.nombre;
      prefs.userName = bloc.usuario;
      prefs.password = bloc.password;
      prefs.access_token = response.accessToken;
      prefs.refreshToken = response.refreshToken;
      prefs.tipo = response.tipo;

      // Se guarda como String en formato ISO 8601 (estándar y robusto)
      if (_fechaFacturacion != null) {
        prefs.fechaFacturacion = _fechaFacturacion!;
      }

      try {
        final rutas = await VendedorRutaProvider()
            .obtenerRutasAsignadas(response.codigo, response.tipo);

        if (rutas.isEmpty && mounted) {
          final seleccion = await AppNavigator.pushNamed(
            AppRoutes.rutas,
            arguments: {'multiSelect': true, 'obligatorio': true},
          );
          final nuevas = List<RutasModel>.from(seleccion);
          await VendedorRutaProvider().guardarRutasAsignadas(response.codigo,
              response.tipo, nuevas.map((r) => r.codigo).toList());
        }

        if (mounted) AppNavigator.pushReplacementNamed(AppRoutes.home);
      } catch (e) {
        print("Error al obtener/guardar rutas asignadas: $e");
        if (mounted) {
          alertUtil.showAlertDialog(context,
              'No se pudieron obtener las rutas del vendedor. Intente nuevamente.',
              Icons.error_outline);
        }
      }
    } else if (mounted) {
```

reemplaza por:

```dart
    if (resp.status == 200 && mounted) {
      LoginResponseModel response = LoginResponseModel.fromJson(resp.detalle);
      prefs.vendedor = response.codigo;
      prefs.name = response.nombre;
      prefs.userName = bloc.usuario;
      prefs.password = bloc.password;
      prefs.access_token = response.accessToken;
      prefs.refreshToken = response.refreshToken;
      prefs.tipo = response.tipo;

      // Se guarda como String en formato ISO 8601 (estándar y robusto)
      if (_fechaFacturacion != null) {
        prefs.fechaFacturacion = _fechaFacturacion!;
      }

      if (response.mustChangePassword) {
        if (mounted) {
          await AppNavigator.pushReplacementNamed(
            AppRoutes.cambiarClaveObligatorio,
            arguments: {'claveActual': bloc.password},
          );
        }
        if (mounted) {
          setState(() => _isLoading = false);
        }
        return;
      }

      try {
        final rutas = await VendedorRutaProvider()
            .obtenerRutasAsignadas(response.codigo, response.tipo);

        if (rutas.isEmpty && mounted) {
          final seleccion = await AppNavigator.pushNamed(
            AppRoutes.rutas,
            arguments: {'multiSelect': true, 'obligatorio': true},
          );
          final nuevas = List<RutasModel>.from(seleccion);
          await VendedorRutaProvider().guardarRutasAsignadas(response.codigo,
              response.tipo, nuevas.map((r) => r.codigo).toList());
        }

        if (mounted) AppNavigator.pushReplacementNamed(AppRoutes.home);
      } catch (e) {
        print("Error al obtener/guardar rutas asignadas: $e");
        if (mounted) {
          alertUtil.showAlertDialog(context,
              'No se pudieron obtener las rutas del vendedor. Intente nuevamente.',
              Icons.error_outline);
        }
      }
    } else if (mounted) {
```

(El `return` temprano evita que el bloque `if (mounted) { setState(() => _isLoading = false); }` del final de `_login()` se ejecute dos veces — se maneja explícitamente antes de retornar en la rama de `mustChangePassword`.)

- [ ] **Step 2: Analizar y correr toda la suite**

Run: `flutter analyze`
Expected: sin errores nuevos.

Run: `flutter test`
Expected: sin regresiones (los tests de `login_page_test.dart` no ejercitan `_login()`, deberían seguir pasando igual).

- [ ] **Step 3: Commit**

```bash
git add lib/src/page/login/login.page.dart
git commit -m "feat: fuerza el cambio de clave tras el login si mustChangePassword=true"
```

---

## Task 8: Web — modelo `User` y `AuthService`

**Files:**
- Modify: `src/app/core/models/user.ts`
- Modify: `src/app/core/service/auth.service.ts`

**Interfaces:**
- Produces (usado por Tasks 9-11): `User.mustChangePassword: boolean`. `AuthService.resetPassword` **eliminado**.

Cambio de tipos + eliminación de un método sin lógica propia — sin TDD dedicado (mismo criterio que otros ajustes de modelo/servicio delgado en este proyecto). Se verifica con `tsc --noEmit`.

- [ ] **Step 1: Agregar el campo a `User`**

En `src/app/core/models/user.ts`:

```ts
export class User {
  id!: number;
  username!: string;
  password!: string;
  firstName!: string;
  lastName!: string;
  token!: string;
  refreshToken!: string;
  expiresInSeconds!: number;
}
```

reemplaza por:

```ts
export class User {
  id!: number;
  username!: string;
  password!: string;
  firstName!: string;
  lastName!: string;
  token!: string;
  refreshToken!: string;
  expiresInSeconds!: number;
  mustChangePassword!: boolean;
}
```

- [ ] **Step 2: Eliminar `resetPassword` de `AuthService`**

En `src/app/core/service/auth.service.ts`:

```ts
  private forgotPasswordUrl = `${environment.authUrl}/forgot-password`;
  private resetPasswordUrl = `${environment.authUrl}/reset-password`;
  private changePasswordUrl = `${environment.apiUrl}/usuario/cambiar-clave`;
```

reemplaza por:

```ts
  private forgotPasswordUrl = `${environment.authUrl}/forgot-password`;
  private changePasswordUrl = `${environment.apiUrl}/usuario/cambiar-clave`;
```

```ts
resetPassword(username: string, codigo: string, claveNueva: string) {
  return this.httpClient.post<void>(this.resetPasswordUrl, { username, codigo, claveNueva });
}
```

se elimina por completo.

- [ ] **Step 3: Verificar compilación**

Run: `npx tsc -p tsconfig.app.json --noEmit`
Expected: errores esperados en `reset.component.ts`/`.spec.ts` (todavía llaman a `resetPassword`, se eliminan en la Task 9) — si el error apunta solo a esos 2 archivos, es correcto en este punto intermedio; si aparece en otro archivo, revisar.

- [ ] **Step 4: Commit**

```bash
git add src/app/core/models/user.ts src/app/core/service/auth.service.ts
git commit -m "feat: agrega mustChangePassword a User y elimina AuthService.resetPassword"
```

---

## Task 9: Web — eliminar el flujo de código de 6 dígitos

**Files:**
- Delete: `src/app/authentication/reset/` (carpeta completa: `.ts`, `.html`, `.sass`, `.spec.ts`)
- Modify: `src/app/authentication/auth.routes.ts`
- Modify: `src/app/authentication/forgot/forgot.component.ts`
- Modify: `src/app/authentication/forgot/forgot.component.html`
- Modify: `src/app/authentication/forgot/forgot.component.spec.ts`

**Interfaces:**
- Consumes: `AuthService.resetPassword` ya eliminado (Task 8) — este es el paso que termina de quitar sus últimos usos.

- [ ] **Step 1: Eliminar el componente `reset`**

```bash
rm -r src/app/authentication/reset
```

- [ ] **Step 2: Quitar la ruta**

En `src/app/authentication/auth.routes.ts`:

```ts
import { ForgotComponent } from "./forgot/forgot.component";
import { ResetComponent } from "./reset/reset.component";
export const AUTH_ROUTE: Route[] = [
  {
    path: '',
    redirectTo: 'signin',
    pathMatch: 'full',
  },
  {
    path: 'signin',
    component: SigninComponent,
  },
  {
    path: 'forgot',
    component: ForgotComponent,
  },
  {
    path: 'reset',
    component: ResetComponent,
  },
```

reemplaza por:

```ts
import { ForgotComponent } from "./forgot/forgot.component";
export const AUTH_ROUTE: Route[] = [
  {
    path: '',
    redirectTo: 'signin',
    pathMatch: 'full',
  },
  {
    path: 'signin',
    component: SigninComponent,
  },
  {
    path: 'forgot',
    component: ForgotComponent,
  },
```

- [ ] **Step 3: Quitar `irARestablecer` de `ForgotComponent`**

En `src/app/authentication/forgot/forgot.component.ts`:

```ts
  onSubmit() {
    this.submitted = true;
    if (this.form.invalid) {
      return;
    }

    this.loading = true;
    this.authService.forgotPassword(this.f['usernameOrEmail'].value).subscribe({
      next: () => {
        this.loading = false;
        // Siempre respondemos igual exista o no la cuenta -- no filtramos
        // qué usuarios/correos están registrados.
        this.enviado = true;
      },
      error: () => {
        this.loading = false;
        this.enviado = true;
      },
    });
  }

  irARestablecer() {
    const valor = (this.f['usernameOrEmail'].value ?? '').toString();
    this.router.navigate(['/authentication/reset'], {
      // Reset-password exige el username exacto, no el correo -- solo se
      // prellena si el usuario ya escribió su username (no un correo).
      queryParams: valor.includes('@') ? {} : { username: valor },
    });
  }
}
```

reemplaza por:

```ts
  onSubmit() {
    this.submitted = true;
    if (this.form.invalid) {
      return;
    }

    this.loading = true;
    this.authService.forgotPassword(this.f['usernameOrEmail'].value).subscribe({
      next: () => {
        this.loading = false;
        // Siempre respondemos igual exista o no la cuenta -- no filtramos
        // qué usuarios/correos están registrados.
        this.enviado = true;
      },
      error: () => {
        this.loading = false;
        this.enviado = true;
      },
    });
  }
}
```

(El constructor de `ForgotComponent` sigue recibiendo `Router` como parámetro aunque ya no lo use dentro del cuerpo — se mantiene por ahora para no romper la firma que el spec/otros archivos podrían referenciar; si `tsc`/lint marcan el parámetro como no usado, es un warning preexistente aceptable, no un error de compilación.)

- [ ] **Step 4: Actualizar el HTML de `forgot.component.html`**

Abrir `src/app/authentication/forgot/forgot.component.html` y quitar cualquier botón/enlace que llame a `irARestablecer()` (p.ej. un `<button (click)="irARestablecer()">...</button>` dentro del bloque que se muestra cuando `enviado` es `true`). El resto del template (formulario de "usuario o correo" y el mensaje de éxito) no cambia.

- [ ] **Step 5: Actualizar `forgot.component.spec.ts`**

Eliminar por completo el bloque `describe('irARestablecer', ...)` (los 2 tests que verifican la navegación a `/authentication/reset`). El resto del spec (`should create`, `onSubmit`) no cambia.

- [ ] **Step 6: Ejecutar el spec y verificar que pasa**

Run: `ng test --include='**/forgot.component.spec.ts'`
Expected: PASS (4 tests — antes 6, se quitaron los 2 de `irARestablecer`).

- [ ] **Step 7: Verificar compilación completa**

Run: `npx tsc -p tsconfig.app.json --noEmit`
Expected: sin errores.

- [ ] **Step 8: Commit**

```bash
git add src/app/authentication/auth.routes.ts \
        src/app/authentication/forgot/forgot.component.ts \
        src/app/authentication/forgot/forgot.component.html \
        src/app/authentication/forgot/forgot.component.spec.ts
git rm -r src/app/authentication/reset
git commit -m "feat: elimina el flujo de código de 6 dígitos en el web client"
```

---

## Task 10: Web — diálogo de cambio de clave obligatorio

**Files:**
- Create: `src/app/perfil/cambiar-clave-obligatorio/cambiar-clave-obligatorio.component.ts`
- Create: `src/app/perfil/cambiar-clave-obligatorio/cambiar-clave-obligatorio.component.html`
- Create: `src/app/perfil/cambiar-clave-obligatorio/cambiar-clave-obligatorio.component.scss` (vacío)
- Test: `src/app/perfil/cambiar-clave-obligatorio/cambiar-clave-obligatorio.component.spec.ts`

**Interfaces:**
- Consumes: `AuthService.changePassword` (ya existente).
- Produces (usado por Task 11): `CambiarClaveObligatorioComponent` (standalone), `@Input() claveActualForzada!: string`, cierra el modal con `activeModal.close()` al completar exitosamente (no acepta `dismiss()` — sin botón de cerrar en el template).

- [ ] **Step 1: Escribir el spec que falla**

Crear `src/app/perfil/cambiar-clave-obligatorio/cambiar-clave-obligatorio.component.spec.ts`:

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { NgbActiveModal } from '@ng-bootstrap/ng-bootstrap';
import { environment } from 'environments/environment';

import { CambiarClaveObligatorioComponent } from './cambiar-clave-obligatorio.component';

describe('CambiarClaveObligatorioComponent', () => {
  let component: CambiarClaveObligatorioComponent;
  let fixture: ComponentFixture<CambiarClaveObligatorioComponent>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CambiarClaveObligatorioComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), NgbActiveModal]
    }).compileComponents();

    fixture = TestBed.createComponent(CambiarClaveObligatorioComponent);
    component = fixture.componentInstance;
    component.claveActualForzada = 'ClaveTemp123';
    httpMock = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('guarda la clave nueva usando la clave temporal recibida y cierra el modal', () => {
    component.form.patchValue({ claveNueva: 'claveNueva123', confirmarClave: 'claveNueva123' });
    const closeSpy = spyOn(component.activeModal, 'close');

    component.submit();

    const req = httpMock.expectOne(`${environment.apiUrl}/usuario/cambiar-clave`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ claveActual: 'ClaveTemp123', claveNueva: 'claveNueva123' });
    req.flush(undefined);

    expect(closeSpy).toHaveBeenCalled();
  });

  it('muestra un mensaje de error si falla el cambio', () => {
    component.form.patchValue({ claveNueva: 'claveNueva123', confirmarClave: 'claveNueva123' });

    component.submit();

    const req = httpMock.expectOne(`${environment.apiUrl}/usuario/cambiar-clave`);
    req.flush({ message: 'error' }, { status: 500, statusText: 'Server Error' });

    expect(component.error).toBe('No se pudo cambiar la clave. Intente nuevamente.');
  });

  it('no permite guardar si las claves no coinciden', () => {
    component.form.patchValue({ claveNueva: 'claveNueva123', confirmarClave: 'otraClave1' });

    component.submit();

    httpMock.expectNone(`${environment.apiUrl}/usuario/cambiar-clave`);
  });
});
```

- [ ] **Step 2: Ejecutar el spec y verificar que falla**

Run: `ng test --include='**/cambiar-clave-obligatorio.component.spec.ts'`
Expected: FALLA — el componente no existe.

- [ ] **Step 3: Crear el componente**

Crear `src/app/perfil/cambiar-clave-obligatorio/cambiar-clave-obligatorio.component.scss` (vacío).

Crear `src/app/perfil/cambiar-clave-obligatorio/cambiar-clave-obligatorio.component.ts`:

```ts
import { Component, Input } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  ValidationErrors,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import { NgbActiveModal } from '@ng-bootstrap/ng-bootstrap';
import { AuthService } from '@core';

function clavesCoincidenValidator(): ValidatorFn {
  return (group: AbstractControl): ValidationErrors | null => {
    const nueva = group.get('claveNueva')?.value;
    const confirmar = group.get('confirmarClave')?.value;
    return nueva === confirmar ? null : { clavesNoCoinciden: true };
  };
}

@Component({
  selector: 'app-cambiar-clave-obligatorio',
  imports: [ReactiveFormsModule],
  templateUrl: './cambiar-clave-obligatorio.component.html',
  styleUrl: './cambiar-clave-obligatorio.component.scss',
})
export class CambiarClaveObligatorioComponent {
  @Input() claveActualForzada!: string;

  form: FormGroup;
  submitted = false;
  loading = false;
  error = '';

  constructor(public activeModal: NgbActiveModal, private authService: AuthService) {
    this.form = new FormGroup(
      {
        claveNueva: new FormControl<string>('', [Validators.required, Validators.minLength(8)]),
        confirmarClave: new FormControl<string>('', Validators.required),
      },
      { validators: clavesCoincidenValidator() },
    );
  }

  get f() {
    return this.form.controls;
  }

  submit(): void {
    this.submitted = true;
    this.error = '';

    if (this.form.invalid) {
      return;
    }

    this.loading = true;
    this.authService
      .changePassword(this.claveActualForzada, this.f['claveNueva'].value)
      .subscribe({
        next: () => {
          this.loading = false;
          this.activeModal.close();
        },
        error: (err: HttpErrorResponse) => {
          this.loading = false;
          this.error = 'No se pudo cambiar la clave. Intente nuevamente.';
        },
      });
  }
}
```

Crear `src/app/perfil/cambiar-clave-obligatorio/cambiar-clave-obligatorio.component.html`:

```html
<div class="modal-header">
  <h5 class="modal-title">Debes cambiar tu clave</h5>
</div>

<div class="modal-body">
  <p>Por seguridad, debes elegir una clave nueva antes de continuar.</p>
  <form [formGroup]="form" (ngSubmit)="submit()">
    <div class="form-group">
      <label>Clave nueva</label>
      <input type="password" class="form-control" formControlName="claveNueva"
        [class.is-invalid]="submitted && f['claveNueva'].errors">
      @if (submitted && f['claveNueva'].errors) {
        <div class="invalid-feedback">La clave nueva debe tener al menos 8 caracteres.</div>
      }
    </div>
    <div class="form-group">
      <label>Confirmar clave nueva</label>
      <input type="password" class="form-control" formControlName="confirmarClave"
        [class.is-invalid]="submitted && (f['confirmarClave'].errors || form.errors?.['clavesNoCoinciden'])">
      @if (submitted && form.errors?.['clavesNoCoinciden']) {
        <div class="invalid-feedback">Las claves no coinciden.</div>
      }
    </div>
    @if (error) {
      <div class="alert alert-danger">{{ error }}</div>
    }
  </form>
</div>

<div class="modal-footer">
  <button type="button" class="btn btn-primary" [disabled]="loading" (click)="submit()">
    @if (loading) {
      <span class="spinner-border spinner-border-sm me-1"></span>
    }
    Guardar
  </button>
</div>
```

(Nota: no hay `btn-close`/botón "Cancelar" en `modal-header`/`modal-footer` — es intencional, el modal no se puede descartar. `SigninComponent`, en la Task 11, lo abre con `{ backdrop: 'static', keyboard: false }`.)

- [ ] **Step 4: Ejecutar el spec y verificar que pasa**

Run: `ng test --include='**/cambiar-clave-obligatorio.component.spec.ts'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/app/perfil/cambiar-clave-obligatorio
git commit -m "feat: agrega el diálogo bloqueante de cambio de clave obligatorio"
```

---

## Task 11: Web — `SigninComponent` fuerza el diálogo tras el login

**Files:**
- Modify: `src/app/authentication/signin/signin.component.ts`
- Modify: `src/app/authentication/signin/signin.component.spec.ts`

**Interfaces:**
- Consumes: `User.mustChangePassword` (Task 8), `CambiarClaveObligatorioComponent` (Task 10).

- [ ] **Step 1: Escribir el test que falla**

En `src/app/authentication/signin/signin.component.spec.ts`, agregar `NgbModal` al setup y un nuevo `describe`:

En el bloque `beforeEach`, agregar junto a los otros mocks:

```ts
    authServiceMock = {
      login: jasmine.createSpy('login').and.returnValue(of({ token: 'test-token' })),
      currentUserValue: { token: 'test-token' }
    };
```

reemplaza por:

```ts
    authServiceMock = {
      login: jasmine.createSpy('login').and.returnValue(of({ token: 'test-token', mustChangePassword: false })),
      currentUserValue: { token: 'test-token', mustChangePassword: false }
    };
```

y agregar el mock de `NgbModal`:

```ts
    rememberedAccountsServiceMock = {
      getAccounts: jasmine.createSpy('getAccounts').and.returnValue([]),
      saveAccount: jasmine.createSpy('saveAccount')
    };

    formBuilder = new UntypedFormBuilder();

    component = new SigninComponent(
      formBuilder,
      routerMock,
      authServiceMock as AuthService,
      productoServiceMock as ProductoService,
      rememberedAccountsServiceMock as RememberedAccountsService
    );
```

reemplaza por:

```ts
    rememberedAccountsServiceMock = {
      getAccounts: jasmine.createSpy('getAccounts').and.returnValue([]),
      saveAccount: jasmine.createSpy('saveAccount')
    };

    ngbModalMock = {
      open: jasmine.createSpy('open').and.returnValue({
        componentInstance: {},
        closed: of(undefined),
      }),
    };

    formBuilder = new UntypedFormBuilder();

    component = new SigninComponent(
      formBuilder,
      routerMock,
      authServiceMock as AuthService,
      productoServiceMock as ProductoService,
      rememberedAccountsServiceMock as RememberedAccountsService,
      ngbModalMock as unknown as NgbModal
    );
```

y declarar la variable junto a las otras (`let ngbModalMock: any;`) al inicio del `describe`.

Agregar el import de `NgbModal`:

```ts
import { AuthService, RememberedAccountsService } from '@core';
```

reemplaza por:

```ts
import { AuthService, RememberedAccountsService } from '@core';
import { NgbModal } from '@ng-bootstrap/ng-bootstrap';
import { CambiarClaveObligatorioComponent } from 'app/perfil/cambiar-clave-obligatorio/cambiar-clave-obligatorio.component';
```

Y agregar, al final del archivo (dentro de `describe('SigninComponent', ...)`, después del `describe('ngOnInit', ...)` existente):

```ts
  describe('onSubmit con mustChangePassword', () => {
    beforeEach(() => {
      component.ngOnInit();
      component.loginForm.get('username')?.setValue('jperez');
      component.loginForm.get('password')?.setValue('claveTemp123');
    });

    it('navega a home si mustChangePassword es false', () => {
      component.onSubmit();
      expect(routerMock.navigate).toHaveBeenCalledWith(['/']);
      expect(ngbModalMock.open).not.toHaveBeenCalled();
    });

    it('abre el diálogo bloqueante si mustChangePassword es true, sin navegar', () => {
      authServiceMock.login.and.returnValue(of({ token: 'test-token', mustChangePassword: true }));
      authServiceMock.currentUserValue = { token: 'test-token', mustChangePassword: true };

      component.onSubmit();

      expect(ngbModalMock.open).toHaveBeenCalledWith(
        CambiarClaveObligatorioComponent,
        jasmine.objectContaining({ backdrop: 'static', keyboard: false }),
      );
      expect(routerMock.navigate).not.toHaveBeenCalledWith(['/']);
    });

    it('pasa la clave recién escrita al diálogo', () => {
      authServiceMock.login.and.returnValue(of({ token: 'test-token', mustChangePassword: true }));
      authServiceMock.currentUserValue = { token: 'test-token', mustChangePassword: true };
      const modalRef = { componentInstance: {} as any, closed: of(undefined) };
      ngbModalMock.open.and.returnValue(modalRef);

      component.onSubmit();

      expect(modalRef.componentInstance.claveActualForzada).toBe('claveTemp123');
    });
  });
```

- [ ] **Step 2: Ejecutar y verificar que falla**

Run: `ng test --include='**/signin.component.spec.ts'`
Expected: FALLA — `SigninComponent` aún no acepta `NgbModal` en el constructor ni abre el diálogo.

- [ ] **Step 3: Implementar en `SigninComponent`**

En `src/app/authentication/signin/signin.component.ts`, agregar los imports:

```ts
import { AuthService, RememberedAccountsService } from '@core';
import { ProductoService } from 'app/services/producto.service';
```

reemplaza por:

```ts
import { AuthService, RememberedAccountsService } from '@core';
import { ProductoService } from 'app/services/producto.service';
import { NgbModal } from '@ng-bootstrap/ng-bootstrap';
import { CambiarClaveObligatorioComponent } from 'app/perfil/cambiar-clave-obligatorio/cambiar-clave-obligatorio.component';
```

El constructor:

```ts
  constructor(
    private formBuilder: UntypedFormBuilder,
    private router: Router,
    private authService: AuthService,
    private productoService: ProductoService,
    private rememberedAccountsService: RememberedAccountsService
  ) { }
```

reemplaza por:

```ts
  constructor(
    private formBuilder: UntypedFormBuilder,
    private router: Router,
    private authService: AuthService,
    private productoService: ProductoService,
    private rememberedAccountsService: RememberedAccountsService,
    private modalService: NgbModal
  ) { }
```

Y el bloque `next` de `onSubmit()`:

```ts
          next: (res) => {
            if (res) {
              if (res) {
                const token = this.authService.currentUserValue.token;
                if (token) {
                  if (this.f['remember'].value) {
                    this.rememberedAccountsService.saveAccount(
                      this.f['username'].value,
                      this.f['password'].value
                    );
                  }
                  this.productoService.loadProductos().subscribe({
                    next: () => console.log('Productos cargados en segundo plano'),
                    error: (err) => console.error('Error cargando productos post-login', err)
                  });
                  this.router.navigate(['/']);
                }
              } else {
                this.error = 'Usuario inválido';
              }
            } else {
              this.error = 'Usuario inválido';
            }
          },
```

reemplaza por:

```ts
          next: (res) => {
            if (res) {
              if (res) {
                const token = this.authService.currentUserValue.token;
                if (token) {
                  if (this.f['remember'].value) {
                    this.rememberedAccountsService.saveAccount(
                      this.f['username'].value,
                      this.f['password'].value
                    );
                  }
                  this.productoService.loadProductos().subscribe({
                    next: () => console.log('Productos cargados en segundo plano'),
                    error: (err) => console.error('Error cargando productos post-login', err)
                  });
                  if (this.authService.currentUserValue.mustChangePassword) {
                    const modalRef = this.modalService.open(CambiarClaveObligatorioComponent, {
                      backdrop: 'static',
                      keyboard: false,
                    });
                    modalRef.componentInstance.claveActualForzada = this.f['password'].value;
                    modalRef.closed.subscribe(() => {
                      this.authService.logout();
                      this.router.navigate(['/authentication/signin']);
                    });
                  } else {
                    this.router.navigate(['/']);
                  }
                }
              } else {
                this.error = 'Usuario inválido';
              }
            } else {
              this.error = 'Usuario inválido';
            }
          },
```

- [ ] **Step 4: Ejecutar y verificar que pasa**

Run: `ng test --include='**/signin.component.spec.ts'`
Expected: PASS (todos los tests previos + los 3 nuevos).

- [ ] **Step 5: Verificar compilación completa y correr la suite entera**

Run: `npx tsc -p tsconfig.app.json --noEmit`
Expected: sin errores.

Run: `ng test --watch=false`
Expected: sin regresiones frente al conteo previo a esta rama.

- [ ] **Step 6: Commit**

```bash
git add src/app/authentication/signin/signin.component.ts \
        src/app/authentication/signin/signin.component.spec.ts
git commit -m "feat: fuerza el diálogo de cambio de clave tras el login si mustChangePassword=true"
```

---

## Self-Review (completado durante la escritura del plan)

**Cobertura del spec:**
- `AppUser.mustChangePassword` + migración → Task 1.
- Flujo unificado de "olvidé mi clave" (clave temporal, sin código, elimina `PasswordResetToken`/`/auth/reset-password`) + `mustChangePassword` en login/weblogin → Task 2.
- `crear()` marca el flag, `cambiarClave()` lo limpia → Task 3.
- Correo con botón condicional a `ROLE_ADMIN` → Task 2 (ya incluye ambos métodos de `EmailService`).
- Mobile: modelo, simplificación de "olvidé mi clave", pantalla bloqueante, wiring en login → Tasks 4-7.
- Web: modelo/servicio, eliminación del flujo de código, diálogo bloqueante, wiring en signin → Tasks 8-11.

**Placeholders:** ninguno — todos los pasos incluyen código completo o el diff exacto a aplicar.

**Consistencia de tipos:** `TokenResponse`/`WebLoginRes` (Java, Task 2) agregan `mustChangePassword` al final; `LoginResponseModel` (Dart, Task 4) y `User` (TypeScript, Task 8) usan el mismo nombre de campo. `EmailService.enviarCredencialesIniciales`/`enviarClaveTemporalPorOlvido` comparten la firma `(destinatario, username, clave, esAdmin)` en los 2 métodos.

**Dependencias entre tareas verificadas:** Task 2 hace el cambio mínimo necesario en `UsuarioAdminService` (un argumento) para que compile tras cambiar `EmailService`; Task 3 completa el resto de `UsuarioAdminService` sin volver a tocar esa línea. Task 6 (pantalla mobile) no depende de Task 5, pero Task 7 (wiring en login) sí depende de Task 6 (la ruta debe existir). Task 11 (web) depende de Task 10 (el componente del diálogo debe existir antes de importarlo).

**Riesgo identificado y mitigado:** el test `login_usuarioConMustChangePassword_loRetornaEnLaRespuesta` originalmente propuesto para `/auth/login` fallaría por NPE (`u.getVendedor().getId()` sobre un usuario sin vendedor) — se corrigió usando `/auth/weblogin` en su lugar (Task 2, Step 9), que no exige `Vendedor`.
