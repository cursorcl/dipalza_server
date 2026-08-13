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
