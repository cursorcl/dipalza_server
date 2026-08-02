package cl.eos.dipalza.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Habilita @Scheduled en toda la app. Efecto colateral confirmado benigno: revive
// RefreshTokenService.purgeExpiredTokens() (cron "0 0 3 * * *", borra refresh tokens
// expirados), que existia en el codigo pero nunca corria porque @EnableScheduling
// no estaba presente en ningun lado de la aplicacion.
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
