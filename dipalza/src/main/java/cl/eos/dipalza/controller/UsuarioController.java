package cl.eos.dipalza.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cl.eos.dipalza.repository.UserRepo;
import cl.eos.dipalza.service.RefreshTokenService;

@RestController
@RequestMapping("/api/usuario")
@Profile({ "dev-sec", "prod-sec" })
public class UsuarioController {

	private static final int CLAVE_LARGO_MINIMO = 8;

	private final UserRepo users;
	private final PasswordEncoder enc;
	private final RefreshTokenService refreshTokenService;

	public UsuarioController(UserRepo users, PasswordEncoder enc, RefreshTokenService refreshTokenService) {
		this.users = users;
		this.enc = enc;
		this.refreshTokenService = refreshTokenService;
	}

	public record CambiarClaveReq(String claveActual, String claveNueva) {
	}

	@PutMapping("/cambiar-clave")
	public void cambiarClave(Authentication authentication, @RequestBody CambiarClaveReq req) {
		var u = users.findByUsername(authentication.getName())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));

		if (!enc.matches(req.claveActual(), u.getPassword()))
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Clave actual incorrecta");

		if (req.claveNueva() == null || req.claveNueva().length() < CLAVE_LARGO_MINIMO)
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"La clave nueva debe tener al menos " + CLAVE_LARGO_MINIMO + " caracteres");

		if (enc.matches(req.claveNueva(), u.getPassword()))
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La clave nueva debe ser distinta de la actual");

		u.setPassword(enc.encode(req.claveNueva()));
		users.save(u);

		refreshTokenService.revocarTokensDeUsuario(u);
	}
}
