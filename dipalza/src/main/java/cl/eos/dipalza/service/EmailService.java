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

	public void enviarCredencialesIniciales(String destinatario, String username, String claveInicial) {
		var mensaje = new SimpleMailMessage();
		mensaje.setTo(destinatario);
		mensaje.setSubject("Dipalza - Tu cuenta fue creada");
		mensaje.setText("""
				Se creó una cuenta de Dipalza para ti.

				Usuario: %s
				Clave inicial: %s

				Te recomendamos cambiar esta clave la primera vez que inicies sesión.
				""".formatted(username, claveInicial));
		mailSender.send(mensaje);
	}
}
