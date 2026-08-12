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
    void enviarCredencialesIniciales_construyeYEnviaElMensajeCorrecto() throws Exception {
        service.enviarCredencialesIniciales("nuevo@dipalza.cl", "jperez", "Cl4ve!Segura");

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
                .contains("http://localhost:4200/#/perfil")
                .contains("Cambiar mi clave");
    }

    @Test
    void enviarCodigoRecuperacionClave_construyeYEnviaElMensajeCorrectoSinBoton() throws Exception {
        service.enviarCodigoRecuperacionClave("nuevo@dipalza.cl", "123456");

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());

        MimeMessage mensaje = captor.getValue();
        assertThat(mensaje.getAllRecipients()[0].toString()).isEqualTo("nuevo@dipalza.cl");
        assertThat(mensaje.getSubject()).isEqualTo("Dipalza - Código de recuperación de clave");
        String contenido = (String) mensaje.getContent();
        assertThat(contenido)
                .contains("123456")
                .contains("http://localhost:4200/assets/images/logo_dipalza.png")
                .doesNotContain("Cambiar mi clave");
    }
}
