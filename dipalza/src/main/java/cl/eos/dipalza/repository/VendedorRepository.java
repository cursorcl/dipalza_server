package cl.eos.dipalza.repository;

import cl.eos.dipalza.entity.Vendedor;
import cl.eos.dipalza.entity.ids.VendedorId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface VendedorRepository extends JpaRepository<Vendedor, VendedorId> {

	Optional<Vendedor> findFirstByRutOrderByNombreAsc(String rut);


}
