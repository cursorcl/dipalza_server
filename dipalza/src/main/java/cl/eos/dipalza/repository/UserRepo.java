package cl.eos.dipalza.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import cl.eos.dipalza.entity.AppUser;

public interface UserRepo extends JpaRepository<AppUser, Long> {
	  Optional<AppUser> findByUsername(String username);
	  Optional<AppUser> findByEmail(String email);
	}


