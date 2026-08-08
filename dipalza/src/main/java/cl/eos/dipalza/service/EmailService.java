package cl.eos.dipalza.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

// Solo se usa desde AuthController (recuperación de clave), que ya está
// restringido a estos perfiles; evita exigir configuración SMTP en
// dev-nosec/it, donde no se levanta AuthController.
@Service
@Profile({ "dev-sec", "prod-sec" })
public class EmailService {

	@Autowired
	private JavaMailSender mailSender;

	public void enviarCodigoRecuperacionClave(String destinatario, String codigo) {
		var mensaje = new SimpleMailMessage();
		mensaje.setTo(destinatario);
		mensaje.setSubject("Dipalza - Código de recuperación de clave");
		mensaje.setText("""
				Recibimos una solicitud para restablecer tu clave de Dipalza.

				Tu código de recuperación es: %s

				Este código vence en 30 minutos. Si no solicitaste este cambio, ignora este correo.
				""".formatted(codigo));
		mailSender.send(mensaje);
	}
}
