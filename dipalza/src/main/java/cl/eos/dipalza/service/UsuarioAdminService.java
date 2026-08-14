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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

// Depende de EmailService (credenciales iniciales), que solo existe en los
// perfiles con seguridad; se restringe a los mismos perfiles que
// UsuarioAdminController para no romper el contexto en dev-nosec.
@Service
@Profile({ "dev-sec", "prod-sec" })
public class UsuarioAdminService {

    private static final Logger log = LoggerFactory.getLogger(UsuarioAdminService.class);

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
        u.setMustChangePassword(true);
        u.setVendedor(vendedor);
        userRepo.save(u);

        boolean correoEnviado = false;
        if (email != null) {
            try {
                // Hoy siempre es false: este flujo no asigna roles (se asignan
                // fuera de la gestión de usuarios), así que el correo de
                // credenciales iniciales nunca lleva el botón "Cambiar mi clave".
                // Se deja calculado para cuando el alta soporte roles.
                boolean esAdmin = u.getRoles().stream().anyMatch(r -> "ROLE_ADMIN".equals(r.getName()));
                emailService.enviarCredencialesIniciales(email, u.getUsername(), req.password(), esAdmin);
                correoEnviado = true;
            } catch (RuntimeException e) {
                correoEnviado = false;
                log.warn("No se pudo enviar el correo de credenciales iniciales al usuario {} ({})",
                        u.getUsername(), email, e);
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
