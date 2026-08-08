package cl.eos.dipalza.service;

import java.time.Instant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cl.eos.dipalza.entity.AppUser;
import cl.eos.dipalza.repository.RefreshTokenRepo;

@Service
@Transactional // Importante para operaciones DELETE
public class RefreshTokenService {

    @Autowired
    private RefreshTokenRepo refreshTokenRepository;

    @Scheduled(cron = "0 0 3 * * *")
    public void purgeExpiredTokens() {
        Instant now = Instant.now();
        refreshTokenRepository.deleteByExpiresAtBefore(now);
    }

    // Invalida las sesiones activas del usuario tras un cambio de clave
    // (autenticado o vía recuperación), forzando re-login en otros dispositivos.
    public void revocarTokensDeUsuario(AppUser user) {
        refreshTokenRepository.deleteByUser(user);
    }
}