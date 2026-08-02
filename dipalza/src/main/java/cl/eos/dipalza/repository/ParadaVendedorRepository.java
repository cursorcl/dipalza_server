package cl.eos.dipalza.repository;

import cl.eos.dipalza.entity.ParadaVendedor;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface ParadaVendedorRepository
        extends JpaRepository<ParadaVendedor, Long>, JpaSpecificationExecutor<ParadaVendedor> {

    @Override
    @EntityGraph(attributePaths = {"vendedor"})
    List<ParadaVendedor> findAll(Specification<ParadaVendedor> spec);

    @Modifying
    @Transactional
    @Query("update ParadaVendedor p set p.calle = :calle where p.id = :id")
    void actualizarCalle(@Param("id") Long id, @Param("calle") String calle);

    @Modifying
    @Transactional
    @Query("update ParadaVendedor p set p.latitud = :latitud, p.longitud = :longitud, p.horaFin = :horaFin where p.id = :id")
    void actualizarUbicacionYHoraFin(@Param("id") Long id, @Param("latitud") double latitud,
                                      @Param("longitud") double longitud, @Param("horaFin") LocalDateTime horaFin);
}
