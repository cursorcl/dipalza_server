package cl.eos.dipalza.repository;

import cl.eos.dipalza.entity.Numerado;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NumeradoRepository extends JpaRepository<Numerado, Long> {


    @Query("SELECT n FROM Numerado n WHERE n.producto.articulo = :productoId AND n.estado = :estado order by n.id asc")
    List<Numerado> findByProductoIdAndEstadoOrderById(@Param("productoId") String productoId, @Param("estado") String estado);
}
