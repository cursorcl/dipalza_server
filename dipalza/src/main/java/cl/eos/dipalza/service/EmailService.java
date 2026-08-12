package cl.eos.dipalza.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

// Solo se usa desde AuthController (recuperación de clave) y UsuarioAdminService
// (credenciales iniciales), ambos ya restringidos a estos perfiles; evita exigir
// configuración SMTP en dev-nosec/it, donde no se levantan esos controllers/servicios.
@Service
@Profile({ "dev-sec", "prod-sec" })
public class EmailService {

	@Autowired
	private JavaMailSender mailSender;

	@Value("${app.frontend-base-url:http://localhost:4200}")
	private String frontendBaseUrl;

	public void enviarCodigoRecuperacionClave(String destinatario, String codigo) {
		String cuerpo = """
				<p>Recibimos una solicitud para restablecer tu clave de Dipalza.</p>
				<p>Tu código de recuperación es:</p>
				<p style="font-size:28px;font-weight:bold;letter-spacing:4px;text-align:center;margin:16px 0;">%s</p>
				<p>Este código vence en 30 minutos. Si no solicitaste este cambio, ignora este correo.</p>
				""".formatted(codigo);
		enviar(destinatario, "Dipalza - Código de recuperación de clave", cuerpo, null, null);
	}

	public void enviarCredencialesIniciales(String destinatario, String username, String claveInicial) {
		String cuerpo = """
				<p>Se creó una cuenta de Dipalza para ti.</p>
				<p><strong>Usuario:</strong> %s<br/><strong>Clave inicial:</strong> %s</p>
				<p>Te recomendamos cambiar esta clave la primera vez que inicies sesión.</p>
				""".formatted(username, claveInicial);
		enviar(destinatario, "Dipalza - Tu cuenta fue creada", cuerpo, "Cambiar mi clave", frontendBaseUrl + "/#/perfil");
	}

	private void enviar(String destinatario, String asunto, String cuerpoHtml, String textoBoton, String urlBoton) {
		String html = construirHtml(cuerpoHtml, textoBoton, urlBoton);
		try {
			MimeMessage mimeMessage = mailSender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, "UTF-8");
			helper.setTo(destinatario);
			helper.setSubject(asunto);
			helper.setText(html, true);
			mailSender.send(mimeMessage);
		} catch (MessagingException e) {
			throw new MailPreparationException("No se pudo preparar el correo: " + asunto, e);
		}
	}

	private String construirHtml(String cuerpoHtml, String textoBoton, String urlBoton) {
		String logoUrl = frontendBaseUrl + "/assets/images/logo_dipalza.png";
		String boton = (textoBoton == null) ? "" : """
				<tr>
					<td align="center" style="padding:24px 0;">
						<a href="%s" style="background-color:#1b6ec2;color:#ffffff;text-decoration:none;
							padding:12px 28px;border-radius:4px;font-family:Arial,sans-serif;font-size:14px;
							display:inline-block;">%s</a>
					</td>
				</tr>
				""".formatted(urlBoton, textoBoton);
		return """
				<!DOCTYPE html>
				<html>
				<body style="margin:0;padding:0;background-color:#f4f4f4;">
					<table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color:#f4f4f4;padding:24px 0;">
						<tr>
							<td align="center">
								<table role="presentation" width="480" cellpadding="0" cellspacing="0"
									style="background-color:#ffffff;border-radius:8px;overflow:hidden;font-family:Arial,sans-serif;color:#333333;">
									<tr>
										<td align="center" style="padding:24px 0;background-color:#ffffff;">
											<img src="%s" alt="Dipalza" style="max-height:60px;" />
										</td>
									</tr>
									<tr>
										<td style="padding:8px 32px 24px 32px;font-size:14px;line-height:1.5;">
											%s
										</td>
									</tr>
									%s
								</table>
							</td>
						</tr>
					</table>
				</body>
				</html>
				""".formatted(logoUrl, cuerpoHtml, boton);
	}
}
