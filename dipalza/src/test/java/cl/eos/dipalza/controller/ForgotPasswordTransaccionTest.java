package cl.eos.dipalza.controller;

import cl.eos.dipalza.entity.AppUser;
import cl.eos.dipalza.entity.RefreshToken;
import cl.eos.dipalza.repository.RefreshTokenRepo;
import cl.eos.dipalza.repository.UserRepo;
import cl.eos.dipalza.service.EmailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

// A diferencia de PasswordFlowControllerTest, esta clase NO es @Transactional:
// necesita que la transacción del endpoint sea real (y no se una a la del test)
// para poder verificar el rollback releyendo desde la base.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev-sec")
class ForgotPasswordTransaccionTest {

	private static final String USERNAME = "rollback1";

	@Autowired
	MockMvc mockMvc;
	@Autowired
	ObjectMapper objectMapper;
	@Autowired
	UserRepo userRepo;
	@Autowired
	RefreshTokenRepo refreshTokenRepo;
	@Autowired
	PasswordEncoder enc;
	@MockBean
	EmailService emailService;

	@AfterEach
	void limpiar() {
		userRepo.findByUsername(USERNAME).ifPresent(u -> {
			refreshTokenRepo.deleteAll(refreshTokenRepo.findAll().stream()
					.filter(t -> t.getUser().getId().equals(u.getId())).toList());
			userRepo.delete(u);
		});
	}

	@Test
	void forgotPassword_falloDeEnvioDeCorreo_revierteLaRotacionDeClave() throws Exception {
		AppUser u = new AppUser();
		u.setUsername(USERNAME);
		u.setPassword(enc.encode("claveOriginal1"));
		u.setEmail("rollback1@test.cl");
		u = userRepo.save(u);
		RefreshToken rt = new RefreshToken();
		rt.setUser(u);
		rt.setTokenHash("hash-rollback1");
		rt.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
		refreshTokenRepo.save(rt);

		doThrow(new MailSendException("SMTP caído")).when(emailService)
				.enviarClaveTemporalPorOlvido(anyString(), anyString(), anyString(), anyBoolean());

		assertThatThrownBy(() -> mockMvc.perform(post("/auth/forgot-password")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("usernameOrEmail", "rollback1@test.cl")))))
				.hasRootCauseInstanceOf(MailSendException.class);

		AppUser recargado = userRepo.findByUsername(USERNAME).orElseThrow();
		assertThat(enc.matches("claveOriginal1", recargado.getPassword())).isTrue();
		assertThat(recargado.isMustChangePassword()).isFalse();
		final Long userId = recargado.getId();
		assertThat(refreshTokenRepo.findAll().stream().filter(t -> t.getUser().getId().equals(userId))).hasSize(1);
	}
}
