package cl.eos.dipalza.model.proyecciones;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Interface que utilizo para traer los campos necesarios de producto.
 * 
 * En este caso, se evita el listado de numerados, logrando así mayor velocidad.
 */
public interface ProductoResumido {
	String getArticulo();
    String getDescripcion();
    BigDecimal getVentaNeto();
    BigDecimal getPorcIla();
    BigDecimal getPorcCarne();
    String getUnidad();
    BigDecimal getStock();
    String getCodigoila();
    Boolean getNumbered();
    BigDecimal getPieces();
    BigDecimal getStockVentas();
    BigDecimal getPiezasVentas();
    LocalDate getLastUpdate();
    BigDecimal getCosto();
}
