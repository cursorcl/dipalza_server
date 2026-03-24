// file: cl/eos/dipalza/repository/VentaRepository.java
package cl.eos.dipalza.repository;

import cl.eos.dipalza.entity.Venta;
import cl.eos.dipalza.entity.VentaDetalle;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VentaDetalleRepository extends JpaRepository<VentaDetalle, Long>, JpaSpecificationExecutor<Venta> {

	
    @Query("SELECT vd FROM VentaDetalle vd LEFT JOIN FETCH vd.piezasUsadas WHERE vd.venta.id = :ventaId")
    List<VentaDetalle> findDetallesWithPiezas(@Param("ventaId") Long ventaId);
    
    @EntityGraph(attributePaths = {"venta"})
    @Query("SELECT d FROM VentaDetalle d WHERE d.venta.id = :ventaId")
    List<VentaDetalle> findByVentaId(@Param("ventaId") Long ventaId);

    @Query("SELECT vd FROM VentaDetalle vd LEFT JOIN FETCH vd.piezasUsadas WHERE vd.venta.id IN :ventaIds")
    List<VentaDetalle> findDetallesWithPiezasByVentaIds(@Param("ventaIds") List<Long> ventaIds);


    /// Optimización
    // Buscar todos los detalles de una venta específica (Solo cabecera del detalle)
    @EntityGraph(attributePaths = {"producto", "venta"})
    List<VentaDetalle> findAllOptimizedByVentaId(Long ventaId);

    // Buscar un detalle específico por ID (Sin piezas)
    @EntityGraph(attributePaths = {"producto", "venta"})
    @Query("SELECT vd FROM VentaDetalle vd WHERE vd.id = :id")
    Optional<VentaDetalle> findByIdOptimized(@Param("id") Long id);

    @EntityGraph(attributePaths = {
            "producto",
            "piezasUsadas",
            "piezasUsadas.numerado" // Opcional: si necesitas datos del inventario de la pieza
    })
    @Query("SELECT vd FROM VentaDetalle vd WHERE vd.venta.id IN :ventaIds")
    List<VentaDetalle> findAllWithPiezasOptimized(@Param("ventaIds") List<Long> ventaIds);
}
