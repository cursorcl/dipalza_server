package cl.eos.dipalza.service;

import cl.eos.dipalza.repository.RefreshTokenRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock RefreshTokenRepo repo;
    @InjectMocks RefreshTokenService service;

    @Test
    void purgeExpiredTokens_llamaAlRepositoryConInstantePasado() {
        Instant antes = Instant.now();
        service.purgeExpiredTokens();
        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(repo).deleteByExpiresAtBefore(captor.capture());
        assertThat(captor.getValue()).isAfterOrEqualTo(antes);
    }

    @Test
    void purgeExpiredTokens_noLanzaExcepcion() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.purgeExpiredTokens());
    }
}
