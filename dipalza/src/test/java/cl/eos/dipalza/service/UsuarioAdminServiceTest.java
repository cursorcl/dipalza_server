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
