package cl.eos.dipalza.controller;

import cl.eos.dipalza.entity.AppUser;
import cl.eos.dipalza.entity.RefreshToken;
import cl.eos.dipalza.entity.Vendedor;
import cl.eos.dipalza.mapper.VendedorMapper;
import cl.eos.dipalza.model.VendedorDTO;
import cl.eos.dipalza.repository.RefreshTokenRepo;
import cl.eos.dipalza.repository.UserRepo;
import cl.eos.dipalza.repository.VendedorRepository;
import cl.eos.dipalza.service.EmailService;
import cl.eos.dipalza.service.JwtService;
import cl.eos.dipalza.service.RefreshTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/auth")
@Profile({"dev-sec","prod-sec"})
public class AuthController {

	private static final int CLAVE_LARGO_MINIMO = 8;
	private static final long RESET_RATE_LIMIT_SEGUNDOS = 60;

	private final UserRepo users;
	private final PasswordEncoder enc;
	private final JwtService jwt;
	private final RefreshTokenRepo refreshTokenRepo;
	private final VendedorRepository vendedorRepo;
	private final EmailService emailService;
	private final RefreshTokenService refreshTokenService;
	private final SecureRandom secureRandom = new SecureRandom();
	// Throttle simple en memoria contra fuerza bruta / spam de correos;
	// alcanza para el volumen de esta app, no requiere infraestructura externa.
	private final ConcurrentHashMap<String, Instant> ultimaSolicitudPorUsername = new ConcurrentHashMap<>();
	@Value("${security.jwt.refresh-hr}")
	long refreshHr;

	public record LoginReq(String username, String password) {
	}

	public record TokenResponse(String accessToken, String refreshToken, long expiresInSeconds, VendedorDTO vendedor,
			boolean mustChangePassword) {
	}


	public record WebLoginRes(String token, String refreshToken, long expiresInSeconds, long id, String username,
			String firstName, String lastName, boolean mustChangePassword) {
	}

	public record ForgotPasswordReq(String usernameOrEmail) {
	}

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

	@PostMapping("/login")
	public TokenResponse login(@RequestBody LoginReq req) {
		var u = users.findByUsername(req.username())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));


		if (!u.isEnabled() || u.isLocked() || !enc.matches(req.password(), u.getPassword()))
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);

		// 422 (no 409/402/401): esos códigos tienen mensajes fijos hardcodeados en
		// el cliente mobile (VenderdorProvider.loginUsuario) para otros escenarios.
		if (u.getVendedor() == null)
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
					"Esta cuenta no tiene un vendedor asociado y no puede iniciar sesión desde la aplicación móvil.");

		// buscar Vendedor
	    var vendedorOpt = vendedorRepo.findById(u.getVendedor().getId());
	    VendedorDTO vendedorDto = vendedorOpt.map(VendedorMapper::toDto).orElse(null);
		
		
		return generateTokenRes(u, vendedorDto);
	}
	
	@PostMapping("/refresh")
	public TokenResponse refresh(@RequestBody RefreshReq req) {
		
		String hashToken = hashToken(req.refreshToken);
		Optional<RefreshToken> token = refreshTokenRepo.findByTokenHash(hashToken);
		
		if(token.isEmpty())
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		
		
			
		RefreshToken t = token.get();
	    if (t.isRevoked() || !t.getExpiresAt().isAfter(Instant.now())) {
	    	throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
	    }
		t.setRevoked(true);
		refreshTokenRepo.save(t);
		var u = t.getUser();
		
	    var vendedorOpt = vendedorRepo.findFirstByRutOrderByNombreAsc(u.getUsername());
	    VendedorDTO vendedorDto = vendedorOpt.map(VendedorMapper::toDto).orElse(null);
	    
	    return generateTokenRes(u, vendedorDto);
	}
	
	
	private TokenResponse generateTokenRes(AppUser u, VendedorDTO vendedorDTO)
	{
		
		String access = jwt.generateAccess(u);
		String refreshJwt = jwt.generateRefresh(u);
        // Hashear con SHA-256 (NO BCrypt) para guardar en BD
        String refreshHash = hashToken(refreshJwt);
        
		var rt = new RefreshToken();
		rt.setUser(u);
		rt.setTokenHash(refreshHash);
		rt.setExpiresAt(Instant.now().plus(refreshHr, ChronoUnit.HOURS));
		refreshTokenRepo.save(rt);

		return new TokenResponse(access, refreshJwt, refreshHr * 60L * 60L, vendedorDTO, u.isMustChangePassword());
	}
	
	
	@PostMapping("/weblogin")
	public WebLoginRes weblogin(@RequestBody LoginReq req) {
		var u = users.findByUsername(req.username())
				.orElseThrow(() -> new  ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales inválidas"));
		


		if (!u.isEnabled() || u.isLocked() || !enc.matches(req.password(), u.getPassword()))
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);


		if(u.getVendedor() != null)
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);


		String access = jwt.generateAccess(u);

		// refresh aleatorio + hash en BD
		String refreshRaw = UUID.randomUUID().toString() + UUID.randomUUID();
		String refreshHash = new BCryptPasswordEncoder().encode(refreshRaw);
		var rt = new RefreshToken();
		rt.setUser(u);
		rt.setTokenHash(refreshHash);
		rt.setExpiresAt(Instant.now().plus(refreshHr, ChronoUnit.MILLIS));
		refreshTokenRepo.save(rt);
		Long id = u.getId();
		String userName = u.getUsername();
		String firstName = userName;
		String lastName = userName;

		

		return new WebLoginRes(access, refreshRaw, 60L * 10, id, userName, firstName, lastName, u.isMustChangePassword()); // 10 min si así configuraste
	}

	public record RefreshReq(String refreshToken) {
	}


	
	@PostMapping("/webrefresh")
	public WebLoginRes webRefresh(@RequestBody RefreshReq req) {
		// Busca por hash (compara con BCrypt)
		var tokens = refreshTokenRepo.findAll(); // optimiza con consulta; aquí ejemplo simple
		var match = tokens.stream().filter(t -> !t.isRevoked() && t.getExpiresAt().isAfter(Instant.now()))
				.filter(t -> new BCryptPasswordEncoder().matches(req.refreshToken(), t.getTokenHash())).findFirst()
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));

		
		
		// Rotación: revoca el actual y emite nuevo
		match.setRevoked(true);
		refreshTokenRepo.save(match);
		var u = match.getUser();
		
	    // buscar Vendedor
	    var vendedorOpt = vendedorRepo.findFirstByRutOrderByNombreAsc(u.getUsername());
	    if(vendedorOpt.isEmpty())
	    	throw new ResponseStatusException(HttpStatus.NOT_FOUND);
	    
	    Vendedor vendedor = vendedorOpt.get();
	    
		String access = jwt.generateAccess(u);
		String newRefreshRaw = UUID.randomUUID().toString() + ":" + UUID.randomUUID();
		String newRefreshHash = new BCryptPasswordEncoder().encode(newRefreshRaw);
		var rt = new RefreshToken();
		rt.setUser(u);
		rt.setTokenHash(newRefreshHash);
		rt.setExpiresAt(Instant.now().plus(refreshHr, ChronoUnit.HOURS));
		refreshTokenRepo.save(rt);
		Long id = u.getId();
		String userName = u.getUsername();
		String[] names = vendedor.getNombre().split(" ");
		String firstName = names[0];
		String lastName = names.length > 1 ? names[1] : "";
		
		

		return new WebLoginRes(access, newRefreshRaw, 60L * 10, id, userName, firstName, lastName, u.isMustChangePassword()); // 10 min si así configuraste
	}
	
	
	// Transaccional para que la rotación de clave, el flag de cambio obligatorio,
	// la revocación de tokens y el envío del correo sean atómicos: si el SMTP
	// falla, se revierte todo y el usuario conserva su clave (antes quedaba con
	// una clave que nunca recibió y sin sesiones: lockout total). Además mantiene
	// el AppUser attached durante todo el flujo, cerrando la ventana en que un
	// save() de un entity detached podía revertir cambios hechos por un admin.
	@Transactional
	@PostMapping("/forgot-password")
	public void forgotPassword(@RequestBody ForgotPasswordReq req) {
		String clave = req.usernameOrEmail() == null ? "" : req.usernameOrEmail().trim();

		Optional<AppUser> userOpt = clave.contains("@") ? users.findByEmail(clave) : users.findByUsername(clave);

		// Responde siempre igual, exista o no el usuario/correo, para no filtrar
		// qué cuentas están registradas. Las cuentas deshabilitadas o bloqueadas
		// se tratan como inexistentes: no tiene sentido enviarles una clave
		// temporal si igual no pueden iniciar sesión (login valida ambos flags).
		if (userOpt.isEmpty() || userOpt.get().getEmail() == null)
			return;
		if (!userOpt.get().isEnabled() || userOpt.get().isLocked())
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

	private String hashToken(String token) {
	    try {
	        MessageDigest digest = MessageDigest.getInstance("SHA-256");
	        byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
	        return Base64.getEncoder().encodeToString(hash);
	    } catch (Exception e) {
	        throw new RuntimeException("Error hashing token", e);
	    }
	}
}
