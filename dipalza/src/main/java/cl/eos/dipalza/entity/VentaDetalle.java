package cl.eos.dipalza.entity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

@Entity
@Table(name = "venta_detalle")
public class VentaDetalle {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	Long id;

	
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venta_id", nullable = false)
    private Venta venta;

    // La FK real que se persiste
    @Column(name = "producto_id", nullable = false)
    private String productoId;

    // Asociación de solo lectura (no escribe la FK)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "producto_id", insertable = false, updatable = false)
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
	private Integer piezas = 0;
	
	@Column(name = "unidad")
	private String unidad;

	@OneToMany(mappedBy = "ventaDetalle", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<VentaDetallePieza> piezasUsadas = new ArrayList<>();


	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}
	
	public String getProductoId() {
		return productoId;
	}

	public void setProductoId(String productoId) {
		this.productoId = productoId;
	}

	public Producto getProducto() {
		return producto;
	}

	public BigDecimal getCantidad() {
		return cantidad;
	}

	public void setCantidad(BigDecimal cantidad) {
		this.cantidad = cantidad;
	}

	public BigDecimal getPrecioUnitario() {
		return precioUnitario;
	}

	public void setPrecioUnitario(BigDecimal precioUnitario) {
		this.precioUnitario = precioUnitario;
	}

	public BigDecimal getTotalLinea() {
		return totalLinea;
	}

	public void setTotalLinea(BigDecimal totalLinea) {
		this.totalLinea = totalLinea;
	}
	
	

	public Venta getVenta() {
		return venta;
	}

	public void setVenta(Venta venta) {
		this.venta = venta;
	}

	public BigDecimal getPorcentajeDescuento() {
		return porcentajeDescuento;
	}

	public void setPorcentajeDescuento(BigDecimal porcentajeDescuento) {
		this.porcentajeDescuento = porcentajeDescuento;
	}

	public BigDecimal getTotalDescuento() {
		return totalDescuento;
	}

	public void setTotalDescuento(BigDecimal totalDescuento) {
		this.totalDescuento = totalDescuento;
	}

	public BigDecimal getPorcentajeIla() {
		return porcentajeIla;
	}

	public void setPorcentajeIla(BigDecimal porcentajeIla) {
		this.porcentajeIla = porcentajeIla;
	}

	public BigDecimal getTotalIla() {
		return totalIla;
	}

	public void setTotalIla(BigDecimal totalIla) {
		this.totalIla = totalIla;
	}

	public BigDecimal getPorcentajeIva() {
		return porcentajeIva;
	}

	public void setPorcentajeIva(BigDecimal porcentajeIva) {
		this.porcentajeIva = porcentajeIva;
	}

	public BigDecimal getTotalIva() {
		return totalIva;
	}

	public void setTotalIva(BigDecimal totalIva) {
		this.totalIva = totalIva;
	}

	public Integer getPiezas() {
		return piezas;
	}

	public void setPiezas(Integer piezas) {
		this.piezas = piezas;
	}

	public List<VentaDetallePieza> getPiezasUsadas() {
		return piezasUsadas;
	}

	public void setPiezasUsadas(List<VentaDetallePieza> piezasUsadas) {
		this.piezasUsadas = piezasUsadas;
	}

	public String getUnidad() {
		return unidad;
	}

	public void setUnidad(String unidad) {
		this.unidad = unidad;
	}

	
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
