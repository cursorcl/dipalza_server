package cl.eos.dipalza.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import cl.eos.dipalza.entity.PasswordResetToken;

public interface PasswordResetTokenRepo extends JpaRepository<PasswordResetToken, Long> {

	Optional<PasswordResetToken> findByTokenHashAndUsedFalse(String tokenHash);
}
