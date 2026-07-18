package cl.eos.dipalza.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = CacheConfig.class)
class CacheConfigTest {

    @Autowired
    CacheManager cacheManager;

    @Test
    void exponeLasCuatroRegionesDeClientes() {
        assertThat(cacheManager.getCacheNames()).containsExactlyInAnyOrder(
                CacheConfig.CLIENTES_BY_VENDEDOR,
                CacheConfig.CLIENTES_BY_RUTA,
                CacheConfig.CLIENTES_BY_ID,
                CacheConfig.ALL_CLIENTES
        );
    }
}
