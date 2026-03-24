// file: cl/eos/dipalza/repository/VentaRepository.java
package cl.eos.dipalza.repository;

import cl.eos.dipalza.entity.Venta;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface VentaRepository extends JpaRepository<Venta, Long>, JpaSpecificationExecutor<Venta> {



    /**
     * Ejemplo de consulta directa por rango de fechas (opcional).
     */
    @Query("""
           select v
           from Venta v
           where v.fecha >= :desde and v.fecha < :hasta
           """)
    Page<Venta> findByFechaBetween(@Param("desde") java.time.LocalDate desde,
                                   @Param("hasta") java.time.LocalDate hasta,
                                   Pageable pageable);
    
    @Query("""
            select v
            from Venta v
            where v.fecha = :fecha
            """)
     List<Venta> findByFecha(@Param("fecha") java.time.LocalDate fecha);
    
    @Query("SELECT v FROM Venta v WHERE v.vendedor.id.codigo = :vendedorCodigo AND CAST(v.fecha AS date) = :fecha")
    List<Venta> findVentasByVendedorAndFecha(@Param("vendedorCodigo") String vendedorCodigo, @Param("fecha") LocalDate fecha);

	///  obtiene las ventas sin detalle
    @Query("""
    	       SELECT v FROM Venta v
    	       WHERE v.cliente.id.rut = :rutCliente
    	         AND v.cliente.id.codigo = :codigoCliente
    	       ORDER BY v.fecha DESC
    	       """)
    	List<Venta> findVentasByClienteOrderByFechaDesc(
    	        @Param("rutCliente") String rutCliente,
    	        @Param("codigoCliente") String codigoCliente,
    	        Pageable pageable);

	///  obtiene las ventas sin detalle
    @Query("""
			SELECT v FROM Venta v
		 	LEFT JOIN FETCH v.detalles
		    WHERE v.cliente.id.rut = :rutCliente
			 AND v.cliente.id.codigo = :codigoCliente
			 AND v.estado = 'CLOSED'
		    ORDER BY v.fecha DESC
 	       """)
 	List<Venta> findVentasCerradasByClienteOrderByFechaDesc(
 	        @Param("rutCliente") String rutCliente,
 	        @Param("codigoCliente") String codigoCliente,
 	        Pageable pageable);

    @Query("SELECT v FROM Venta v JOIN FETCH v.detalles vd WHERE v.id = :ventaId")
    Venta findVentaWithDetalles(@Param("ventaId") Long ventaId);

 	/// ventas de un día por vendedor (rango [inicio, fin))
    @EntityGraph(value = "Venta.header", type = EntityGraph.EntityGraphType.LOAD)
    @Query("""
       select v
         from Venta v
        where v.vendedor.id.codigo = :codigoVendedor
          and  CAST(v.fecha AS date) = :fecha
        order by v.fecha desc
    """)
    List<Venta> findHeaderByVendedorAndDia(
        @Param("codigoVendedor") String codigoVendedor,
        @Param("fecha") LocalDate fecha);
    
    
    @Override
    @EntityGraph(attributePaths = {
			"cliente",
			"vendedor",
			"condicionVenta",
			"ruta",
			"ruta.conduccion",
			"detalles",
			"detalles.producto"
        })
	@NonNull
    List<Venta> findAll(@Nullable Specification<Venta> spec);

	// Consulta optimizada para una sola venta con toda su jerarquía
	@EntityGraph(attributePaths = {"detalles", "detalles.producto", "detalles.piezasUsadas"})
	@Query("SELECT v FROM Venta v WHERE v.id = :id")
	Optional<Venta> findFullVentaById(@Param("id") Long id);



	///  Optimización de consultas

	// 1. Búsqueda dinámica de múltiples ventas (Solo Cabeceras)
	@EntityGraph(attributePaths = {"cliente", "vendedor", "condicionVenta", "ruta", "ruta.conduccion"})
	@Query("SELECT v FROM Venta v")
	List<Venta> findAllOptimized(@Nullable Specification<Venta> spec);

	// 2. Búsqueda de una venta específica por ID (Solo Cabecera)
	@EntityGraph(attributePaths = {"cliente", "vendedor", "condicionVenta", "ruta"})
	@Query("SELECT v FROM Venta v WHERE v.id = :id")
	Optional<Venta> findByIdOptimized(@Param("id") Long id);

	// 3. Búsqueda por lista de IDs (Solo Cabecera)
	@EntityGraph(attributePaths = {"cliente", "vendedor", "condicionVenta", "ruta"})
	@Query("SELECT v FROM Venta v WHERE v.id IN :ids")
	List<Venta> findAllByIdInOptimized(@Param("ids") List<Long> ids);




}
