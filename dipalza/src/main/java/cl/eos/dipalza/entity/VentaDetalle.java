package cl.eos.dipalza.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Setter
@Getter
@Entity
@Table(name = "venta_detalle")
public class VentaDetalle {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "venta_id", nullable = false)
    @JsonBackReference
    private Venta venta;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "producto_id", nullable = false) 
    private Producto producto;

	@Column(name = "cantidad", precision = 18, scale = 4, nullable = false)
	private BigDecimal cantidad;

	@Column(name = "precio_unitario", precision = 18, scale = 6, nullable = false)
	private BigDecimal precioUnitario;

	@Column(name = "porc_descuento", precision = 18, scale = 2, nullable = false)
	private BigDecimal porcentajeDescuento;

	@Column(name = "total_descuento", precision = 18, scale = 2, nullable = false)
	private BigDecimal totalDescuento;

	@Column(name = "porc_ila", precision = 18, scale = 2, nullable = false)
	private BigDecimal porcentajeIla;

	@Column(name = "total_ila", precision = 18, scale = 2, nullable = false)
	private BigDecimal totalIla;

	@Column(name = "porc_iva", precision = 18, scale = 2, nullable = false)
	private BigDecimal porcentajeIva;

	@Column(name = "total_iva", precision = 18, scale = 2, nullable = false)
	private BigDecimal totalIva;

	@Column(name = "total_linea", precision = 18, scale = 2, nullable = false)
	private BigDecimal totalLinea;

	@Column(nullable = false)
	private BigDecimal piezas = BigDecimal.ZERO;
	
	@Column(name = "unidad")
	private String unidad;

	@OneToMany(mappedBy = "ventaDetalle", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
	private List<VentaDetallePieza> piezasUsadas = new ArrayList<>();


    /*
	public String getProductoId() {
		return productoId;
	}

	public void setProductoId(String productoId) {
		this.productoId = productoId;
	}
	*/


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VentaDetalle that = (VentaDetalle) o;
        // Si ambos tienen ID, comparamos por ID
        if (id != null && that.id != null) {
            return id.equals(that.id);
        }
        // Si es nuevo, usamos referencia (o una clave de negocio si tienes)
        return super.equals(o); 
    }

    @Override
    public int hashCode() {
        // Retornar una constante es seguro para entidades JPA mutables
        return getClass().hashCode();
    }
}
