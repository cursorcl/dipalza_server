package cl.eos.dipalza.controller;

import cl.eos.dipalza.entity.AppRole;
import cl.eos.dipalza.entity.AppUser;
import cl.eos.dipalza.repository.RoleRepo;
import cl.eos.dipalza.repository.UserRepo;
import cl.eos.dipalza.service.EmailService;
import cl.eos.dipalza.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Cubre la restricción hasRole("ADMIN") sobre /api/usuarios/** con el filter
// chain real (SecurityConfigDevSec + JwtAuthFilter), que los tests @WebMvcTest
// de UsuarioAdminControllerTest no ejercitan porque excluyen la seguridad.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev-sec")
@Transactional
class UsuarioAdminSecurityTest {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	UserRepo userRepo;
	@Autowired
	RoleRepo roleRepo;
	@Autowired
	PasswordEncoder enc;
	@Autowired
	JwtService jwt;
	@MockBean
	EmailService emailService;

	private AppUser crearUsuario(String username, boolean admin) {
		AppUser u = new AppUser();
		u.setUsername(username);
		u.setPassword(enc.encode("claveLarga1"));
		if (admin) {
			AppRole rol = roleRepo.findByName("ROLE_ADMIN").orElseGet(() -> {
				AppRole nuevo = new AppRole();
				nuevo.setName("ROLE_ADMIN");
				return roleRepo.save(nuevo);
			});
			u.setRoles(Set.of(rol));
		}
		return userRepo.save(u);
	}

	@Test
	void listarUsuarios_sinToken_rechaza() throws Exception {
		mockMvc.perform(get("/api/usuarios"))
				.andExpect(status().isForbidden());
	}

	@Test
	void listarUsuarios_usuarioSinRolAdmin_retorna403() throws Exception {
		AppUser u = crearUsuario("seguridad1", false);

		mockMvc.perform(get("/api/usuarios").header("Authorization", "Bearer " + jwt.generateAccess(u)))
				.andExpect(status().isForbidden());
	}

	@Test
	void listarUsuarios_usuarioConRolAdmin_retorna200() throws Exception {
		AppUser u = crearUsuario("seguridad2", true);

		mockMvc.perform(get("/api/usuarios").header("Authorization", "Bearer " + jwt.generateAccess(u)))
				.andExpect(status().isOk());
	}

	@Test
	void obtenerUsuario_usuarioSinRolAdmin_retorna403() throws Exception {
		AppUser u = crearUsuario("seguridad3", false);

		mockMvc.perform(get("/api/usuarios/" + u.getId())
						.header("Authorization", "Bearer " + jwt.generateAccess(u)))
				.andExpect(status().isForbidden());
	}
}
