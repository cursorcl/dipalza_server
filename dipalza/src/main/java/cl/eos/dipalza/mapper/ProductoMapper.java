package cl.eos.dipalza.mapper;

import cl.eos.dipalza.entity.Numerado;
import cl.eos.dipalza.entity.Producto;
import cl.eos.dipalza.model.NumeradoDTO;
import cl.eos.dipalza.model.ProductoDTO;
import cl.eos.dipalza.model.proyecciones.ProductoResumido;
import io.jsonwebtoken.lang.Collections;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

@Component
public class ProductoMapper {

    // Convierte de Entidad -> DTO
    public ProductoDTO toDTO(Producto producto) {
        if (producto == null) return null;

        ProductoDTO dto = new ProductoDTO();
	    dto.setArticulo(producto.getArticulo());
	    dto.setDescripcion(producto.getDescripcion());
	    dto.setVentaNeto(producto.getVentaNeto());
	    dto.setPorcIla(producto.getPorcIla());
	    dto.setPorcCarne(producto.getPorcCarne());
	    dto.setUnidad(producto.getUnidad());
	    dto.setStock(producto.getStock());
	    dto.setCodigoila(producto.getCodigoila());
		dto.setCosto(producto.getCosto());
	    dto.setNumbered(producto.getNumbered());
	    dto.setLastUpdate(producto.getLastUpdate());
	    dto.setPieces(producto.getPieces());
	    dto.setStockVentas(producto.getStockVentas());
	    dto.setPiezasVentas(producto.getPiezasVentas());
	    dto.setNumerados(
	    		producto.getNumerados() == null ? Collections.emptyList() :
	    		producto.getNumerados().stream().map(n -> toDTO(n)).toList());

        return dto;
    }
    
    public ProductoDTO toDTO(ProductoResumido resumen) {
        if (resumen == null) return null;

        ProductoDTO dto = new ProductoDTO();
        dto.setArticulo(resumen.getArticulo());
        dto.setDescripcion(resumen.getDescripcion());
        dto.setVentaNeto(resumen.getVentaNeto());
        dto.setPorcIla(resumen.getPorcIla());
        dto.setPorcCarne(resumen.getPorcCarne());
        dto.setUnidad(resumen.getUnidad());
        dto.setStock(resumen.getStock());
        dto.setCodigoila(resumen.getCodigoila());
        dto.setNumbered(resumen.getNumbered());
        dto.setLastUpdate(resumen.getLastUpdate());
        dto.setNumerados(new ArrayList<>());
        dto.setPieces(resumen.getPieces());
	    dto.setStockVentas(resumen.getStockVentas());
	    dto.setPiezasVentas(resumen.getPiezasVentas());
		dto.setCosto(resumen.getCosto());
        return dto;
    }

    // Convierte de DTO -> Entidad
    public Producto toEntity(ProductoDTO dto) {
        if (dto == null) return null;


        Producto producto = new Producto();
        producto.setArticulo(dto.getArticulo());
	    producto.setDescripcion(dto.getDescripcion());
	    producto.setVentaNeto(dto.getVentaNeto());
	    producto.setPorcIla(dto.getPorcIla());
	    producto.setPorcCarne(dto.getPorcCarne());
	    producto.setUnidad(dto.getUnidad());
	    producto.setStock(dto.getStock());
	    producto.setCodigoila(dto.getCodigoila());
	    producto.setNumbered(dto.getNumbered());
	    producto.setLastUpdate(dto.getLastUpdate());
	    producto.setPieces(dto.getPieces());
		producto.setCosto(dto.getCosto());
	    producto.setNumerados(
	    		dto.getNumerados() == null ? Collections.emptyList() : 
	    		dto.getNumerados().stream().map(n -> toEntity(n, producto)).toList());
        
        return producto;
    }
    
    
    public NumeradoDTO toDTO(Numerado numerado)
    {
    	if(numerado == null) return null;
    	
    	NumeradoDTO dto =  new NumeradoDTO();
    	dto.setActualizadoEn(numerado.getActualizadoEn());
    	dto.setArticulo(numerado.getProducto().getArticulo());
    	dto.setCreadoEn(numerado.getCreadoEn());
    	dto.setEstado(numerado.getEstado());
    	dto.setId(numerado.getId());
    	dto.setNumero(numerado.getNumero());
    	dto.setPeso(numerado.getPeso());
    	return dto;
    }
    
    public Numerado toEntity(NumeradoDTO dto, Producto producto)
    {
    	if(dto == null || producto == null) return null;
    	
    	Numerado entity =  new Numerado();
    	entity.setActualizadoEn(dto.getActualizadoEn());
    	entity.setProducto(producto);
    	entity.setCreadoEn(dto.getCreadoEn());
    	entity.setEstado(dto.getEstado());
    	entity.setId(dto.getId());
    	entity.setNumero(dto.getNumero());
    	
    	return entity;
    	
    	
    }
}
