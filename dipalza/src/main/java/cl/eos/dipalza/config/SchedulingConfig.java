package cl.eos.dipalza.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Habilita @Scheduled en toda la app. Efecto colateral confirmado benigno: revive
// RefreshTokenService.purgeExpiredTokens() (cron "0 0 3 * * *", borra refresh tokens
// expirados), que existia en el codigo pero nunca corria porque @EnableScheduling
// no estaba presente en ningun lado de la aplicacion.
//
// matchIfMissing = true deja el scheduling encendido por defecto en todos lados (dev,
// prod); solo se apaga donde se declara explicitamente app.scheduling.enabled=false
// (ver application-it.yml) -- los *IT.java levantan un ApplicationContext completo
// contra la BD real compartida, y sin esto GeocodificacionRetryService dispararia su
// barrido (llamadas reales a Nominatim + UPDATEs reales) apenas el contexto arranca.
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
