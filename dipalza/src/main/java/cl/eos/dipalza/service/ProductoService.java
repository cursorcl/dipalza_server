package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.Producto;
import cl.eos.dipalza.mapper.ProductoMapper;
import cl.eos.dipalza.model.ProductoDTO;
import cl.eos.dipalza.repository.ProductoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class ProductoService {

    @Autowired
    private ProductoRepository productoRepository;
    
    @Autowired
    private ProductoMapper productoMapper;

    /**
     * Obtiene la lista de productos sin la lista de numerados asociados.
     * @return lista de productos.
     */
    public List<ProductoDTO> getAllProductos() {
        return productoRepository.obtenerTodoResumido()
                .stream()
                .map(productoMapper::toDTO)
                .collect(Collectors.toList());
    }
    
    /**
     * Obtiene la lista de productos con la lista de numerados asociados.
     * @return lista de productos.
     */
    public List<ProductoDTO> getAllProductosWithNumbered() {
        return productoRepository.findAll()
                .stream()
                .map(productoMapper::toDTO)
                .collect(Collectors.toList());
    }
    
   
    
    public List<ProductoDTO> getProductosByDescripcion(String descripcion) {
        return productoRepository.getProductosByDescripcion(descripcion)
                .stream()
                .map(productoMapper::toDTO)
                .collect(Collectors.toList());
    }

    public Optional<ProductoDTO> findProductoById(String articulo) {
        return productoRepository.findById(articulo)
                .map(productoMapper::toDTO);
    }

    public ProductoDTO createOrUpdateProducto(ProductoDTO ProductoDTO) {
        Producto Producto = productoMapper.toEntity(ProductoDTO);
        Producto savedProducto = productoRepository.save(Producto);
        return productoMapper.toDTO(savedProducto);
    }

    public boolean deleteProducto(String articulo) {
        if (productoRepository.existsById(articulo)) {
            productoRepository.deleteById(articulo);
            return true;
        }
        return false;
    }
}