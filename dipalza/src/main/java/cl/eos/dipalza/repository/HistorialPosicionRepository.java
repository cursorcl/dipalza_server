package cl.eos.dipalza.repository;

import cl.eos.dipalza.entity.HistorialPosicion;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface HistorialPosicionRepository extends JpaRepository<HistorialPosicion, String>, JpaSpecificationExecutor<HistorialPosicion> {

    @Override
    @EntityGraph(attributePaths = {"vendedor"})
    List<HistorialPosicion> findAll();
}
