package cl.eos.dipalza.repository;

import cl.eos.dipalza.entity.Producto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductoRepository extends JpaRepository<Producto, String> {

	List<Producto> getProductosByDescripcion(String descripcion);

	@Query("SELECT p FROM Producto p WHERE p.articulo = :articulo")
	Producto findByArticulo(@Param("articulo") String articulo);

	List<Producto> findByNumberedTrue();
}