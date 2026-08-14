package cl.eos.dipalza.controller;

import cl.eos.dipalza.entity.AppUser;
import cl.eos.dipalza.entity.Vendedor;
import cl.eos.dipalza.entity.ids.VendedorId;
import cl.eos.dipalza.repository.UserRepo;
import cl.eos.dipalza.repository.VendedorRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Cubre /auth/login (el endpoint del cliente mobile) -- antes de este test no
// existía ninguna cobertura y una cuenta sin vendedor asociado tumbaba el
// backend con un NullPointerException (500) en vez de un error claro.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev-sec")
@Transactional
class AuthLoginControllerTest {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	ObjectMapper objectMapper;
	@Autowired
	UserRepo userRepo;
	@Autowired
	VendedorRepository vendedorRepo;
	@Autowired
	PasswordEncoder enc;

	@Test
	void login_usuarioSinVendedor_retorna422ConMensajeClaro() throws Exception {
		AppUser u = new AppUser();
		u.setUsername("sinvendedor1");
		u.setPassword(enc.encode("clave12345"));
		u.setEnabled(true);
		u.setLocked(false);
		userRepo.save(u);

		mockMvc.perform(post("/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								Map.of("username", "sinvendedor1", "password", "clave12345"))))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.message").value(
						"Esta cuenta no tiene un vendedor asociado y no puede iniciar sesión desde la aplicación móvil."));
	}

	@Test
	void login_usuarioConVendedor_siguePermitiendoElLoginNormal() throws Exception {
		Vendedor v = new Vendedor(new VendedorId("001", "V"));
		v.setNombre("Vendedor de prueba");
		vendedorRepo.save(v);

		AppUser u = new AppUser();
		u.setUsername("convendedor1");
		u.setPassword(enc.encode("clave12345"));
		u.setEnabled(true);
		u.setLocked(false);
		u.setVendedor(v);
		userRepo.save(u);

		mockMvc.perform(post("/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								Map.of("username", "convendedor1", "password", "clave12345"))))
				.andExpect(status().isOk());
	}
}
