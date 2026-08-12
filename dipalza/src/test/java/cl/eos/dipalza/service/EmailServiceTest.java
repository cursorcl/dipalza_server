package cl.eos.dipalza.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock JavaMailSender mailSender;
    @InjectMocks EmailService service;

    @Test
    void enviarCredencialesIniciales_construyeYEnviaElMensajeCorrecto() {
        service.enviarCredencialesIniciales("nuevo@dipalza.cl", "jperez", "Cl4ve!Segura");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        SimpleMailMessage mensaje = captor.getValue();
        assertThat(mensaje.getTo()).containsExactly("nuevo@dipalza.cl");
        assertThat(mensaje.getSubject()).isEqualTo("Dipalza - Tu cuenta fue creada");
        assertThat(mensaje.getText()).contains("jperez").contains("Cl4ve!Segura");
    }
}
