package cl.eos.dipalza.repository;

import cl.eos.dipalza.entity.Numerado;
import cl.eos.dipalza.model.NumeradoResumenDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NumeradoRepository extends JpaRepository<Numerado, Long> {


    @Query("SELECT n FROM Numerado n WHERE n.producto.articulo = :productoId AND n.estado = :estado order by n.id asc")
    List<Numerado> findByProductoIdAndEstadoOrderById(@Param("productoId") String productoId, @Param("estado") String estado);

    @Query("SELECT n from Numerado n WHERE n.producto.articulo = :productoId")
    List<Numerado> findByProductoId(@Param("productoId") String productoId);

    @Query("SELECT n from Numerado n WHERE n.estado = :estado")
    List<Numerado> findByEstado(@Param("estado") String productoId);

    @Query("""
    SELECT new cl.eos.dipalza.model.NumeradoResumenDTO(
        n.producto.articulo,
        n.producto.descripcion,
        SUM(n.peso),
        COUNT(n)
    )
    FROM Numerado n
    WHERE n.estado = :estado
    GROUP BY n.producto.articulo, n.producto.descripcion
""")
    List<NumeradoResumenDTO> findGroupedByEstado(@Param("estado") String estado);

    @Query("""
    SELECT COUNT(n) > 0 FROM Numerado n
    WHERE n.producto.articulo = :articulo
      AND n.numero = :numero
      AND n.estado IN ('D','R')
      AND (:id IS NULL OR n.id <> :id)
""")
    boolean existsNumeroActivoParaProducto(@Param("articulo") String articulo,
                                            @Param("numero") Integer numero,
                                            @Param("id") Long id);

    boolean existsByProducto_Articulo(String articulo);
}
