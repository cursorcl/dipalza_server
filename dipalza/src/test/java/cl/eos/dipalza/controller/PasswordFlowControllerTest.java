package cl.eos.dipalza.controller;

import cl.eos.dipalza.entity.AppUser;
import cl.eos.dipalza.entity.RefreshToken;
import cl.eos.dipalza.repository.RefreshTokenRepo;
import cl.eos.dipalza.repository.UserRepo;
import cl.eos.dipalza.service.EmailService;
import cl.eos.dipalza.service.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Cubre los endpoints de cambio de clave (autenticado y "olvidé mi clave")
// contra el contexto completo (H2 + JwtAuthFilter reales) -- hoy no existía
// ningún test para el flujo de autenticación de este proyecto.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev-sec")
@Transactional
class PasswordFlowControllerTest {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	ObjectMapper objectMapper;
	@Autowired
	UserRepo userRepo;
	@Autowired
	PasswordEncoder enc;
	@Autowired
	JwtService jwt;
	@Autowired
	RefreshTokenRepo refreshTokenRepo;
	// EmailService requiere SMTP real; se mockea para no enviar correos de
	// verdad y para poder capturar el código generado en los tests.
	@MockBean
	EmailService emailService;

	private AppUser crearUsuario(String username, String claveActual, String email) {
		AppUser u = new AppUser();
		u.setUsername(username);
		u.setPassword(enc.encode(claveActual));
		u.setEmail(email);
		return userRepo.save(u);
	}

	@Test
	void cambiarClave_datosValidos_actualizaLaClaveYRevocaSesiones() throws Exception {
		AppUser u = crearUsuario("cambio1", "claveVieja1", null);
		String token = jwt.generateAccess(u);
		RefreshToken rt = new RefreshToken();
		rt.setUser(u);
		rt.setTokenHash("hash-existente");
		rt.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
		refreshTokenRepo.save(rt);

		mockMvc.perform(put("/api/usuario/cambiar-clave")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								Map.of("claveActual", "claveVieja1", "claveNueva", "claveNueva1"))))
				.andExpect(status().isOk());

		AppUser actualizado = userRepo.findByUsername("cambio1").orElseThrow();
		assertThat(enc.matches("claveNueva1", actualizado.getPassword())).isTrue();
		assertThat(refreshTokenRepo.findAll().stream().filter(t -> t.getUser().getId().equals(u.getId()))).isEmpty();
	}

	@Test
	void cambiarClave_claveActualIncorrecta_retorna401() throws Exception {
		AppUser u = crearUsuario("cambio2", "claveVieja2", null);
		String token = jwt.generateAccess(u);

		mockMvc.perform(put("/api/usuario/cambiar-clave")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								Map.of("claveActual", "incorrecta", "claveNueva", "claveNueva1"))))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void cambiarClave_claveNuevaMuyCorta_retorna400() throws Exception {
		AppUser u = crearUsuario("cambio3", "claveVieja3", null);
		String token = jwt.generateAccess(u);

		mockMvc.perform(put("/api/usuario/cambiar-clave")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								Map.of("claveActual", "claveVieja3", "claveNueva", "corta"))))
				.andExpect(status().isBadRequest());
	}

	@Test
	void cambiarClave_sinToken_retorna401o403() throws Exception {
		mockMvc.perform(put("/api/usuario/cambiar-clave")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								Map.of("claveActual", "x", "claveNueva", "nuevaClave1"))))
				.andExpect(status().is4xxClientError());
	}

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

	@Test
	void forgotPassword_reintentoInmediato_seThrotlleaYNoReenviaCorreo() throws Exception {
		crearUsuario("recupera2", "claveOriginal2", "recupera2@test.cl");
		Map<String, String> body = Map.of("usernameOrEmail", "recupera2@test.cl");

		mockMvc.perform(post("/auth/forgot-password")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(body)))
				.andExpect(status().isOk());
		mockMvc.perform(post("/auth/forgot-password")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(body)))
				.andExpect(status().isOk());

		verify(emailService).enviarClaveTemporalPorOlvido(anyString(), anyString(), anyString(), anyBoolean());
	}

	@Test
	void forgotPassword_correoNoRegistrado_noEnviaCorreoYRespondeOk() throws Exception {
		mockMvc.perform(post("/auth/forgot-password")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(Map.of("usernameOrEmail", "nadie@test.cl"))))
				.andExpect(status().isOk());

		verifyNoInteractions(emailService);
	}

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
}
