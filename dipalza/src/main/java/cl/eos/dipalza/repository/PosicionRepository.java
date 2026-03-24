package cl.eos.dipalza.repository;

import cl.eos.dipalza.entity.Posicion;
import cl.eos.dipalza.entity.ids.VendedorId;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PosicionRepository extends JpaRepository<Posicion, String> {

    @EntityGraph(attributePaths = {"vendedor"})
    List<Posicion> findAll();

    Posicion findByVendedorId(VendedorId vendedorId);
}
