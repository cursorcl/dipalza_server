package cl.eos.dipalza.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    // Definir este bean desactiva el applicationTaskExecutor por defecto autoconfigurado por Spring Boot;
    // cualquier @Async futuro sin executor explicito en otra parte de la app caeria silenciosamente en
    // este pool pequeno y limitado por el throttling de Nominatim, salvo que especifique su propio executor.
    @Bean(name = "deteccionParadaExecutor")
    public Executor deteccionParadaExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("deteccion-parada-");
        executor.initialize();
        return executor;
    }
}
