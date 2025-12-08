package cl.eos.dipalza.repository;


import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import cl.eos.dipalza.entity.Producto;
import cl.eos.dipalza.model.proyecciones.ProductoResumido;

@Repository
public interface ProductoRepository extends JpaRepository<Producto, String> {

	List<Producto> getProductosByDescripcion(String descripcion);
	
	@Query("SELECT p FROM Producto p")
    List<ProductoResumido> obtenerTodoResumido();
}