package cl.eos.dipalza.repository;

import cl.eos.dipalza.entity.Ila;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;


@Repository
public interface IlaRepository extends JpaRepository<Ila, String>{
	
    // ASC
    List<Ila> findAllByOrderByDescripcionAsc();

    // DESC
    List<Ila> findAllByOrderByDescripcionDesc();

    // Con paginación
    Page<Ila> findAllByOrderByDescripcionAsc(Pageable pageable);

    Ila findIlaByValor(float valor);
}
