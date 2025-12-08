package cl.eos.dipalza.service;


import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import cl.eos.dipalza.entity.Producto;
import cl.eos.dipalza.mapper.ProductoMapper;
import cl.eos.dipalza.model.ProductoDTO;
import cl.eos.dipalza.repository.ProductoRepository;

@Service
public class ProductoService {

    @Autowired
    private ProductoRepository productoRepository;
    
    @Autowired
    private ProductoMapper ProductoMapper;

    /**
     * Obtiene la lista de productos sin la lista de numerados asociados.
     * @return lista de productos.
     */
    public List<ProductoDTO> getAllProductos() {
        return productoRepository.obtenerTodoResumido()
                .stream()
                .map(ProductoMapper::toDTO)
                .collect(Collectors.toList());
    }
    
    /**
     * Obtiene la lista de productos con la lista de numerados asociados.
     * @return lista de productos.
     */
    public List<ProductoDTO> getAllProductosWithNumbered() {
        return productoRepository.findAll()
                .stream()
                .map(ProductoMapper::toDTO)
                .collect(Collectors.toList());
    }
    
   
    
    public List<ProductoDTO> getProductosByDescripcion(String descripcion) {
        return productoRepository.getProductosByDescripcion(descripcion)
                .stream()
                .map(ProductoMapper::toDTO)
                .collect(Collectors.toList());
    }

    public Optional<ProductoDTO> findProductoById(String articulo) {
        return productoRepository.findById(articulo)
                .map(ProductoMapper::toDTO);
    }

    public ProductoDTO createOrUpdateProducto(ProductoDTO ProductoDTO) {
        Producto Producto = ProductoMapper.toEntity(ProductoDTO);
        Producto savedProducto = productoRepository.save(Producto);
        return ProductoMapper.toDTO(savedProducto);
    }

    public boolean deleteProducto(String articulo) {
        if (productoRepository.existsById(articulo)) {
            productoRepository.deleteById(articulo);
            return true;
        }
        return false;
    }
}