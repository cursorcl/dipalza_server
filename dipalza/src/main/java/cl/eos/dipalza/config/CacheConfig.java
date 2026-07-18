package cl.eos.dipalza.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String CLIENTES_BY_VENDEDOR = "clientesByVendedor";
    public static final String CLIENTES_BY_RUTA = "clientesByRuta";
    public static final String CLIENTES_BY_ID = "clientesById";
    public static final String ALL_CLIENTES = "allClientes";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(
                CLIENTES_BY_VENDEDOR, CLIENTES_BY_RUTA, CLIENTES_BY_ID, ALL_CLIENTES
        );
        cacheManager.setCaffeine(Caffeine.newBuilder().maximumSize(500));
        return cacheManager;
    }
}
