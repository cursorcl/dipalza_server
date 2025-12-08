package cl.eos.dipalza.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedAttributeNode;
import jakarta.persistence.NamedEntityGraph;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

@NamedEntityGraph(
		  name = "Venta.header",
		  attributeNodes = {
		    @NamedAttributeNode("cliente"),
		    @NamedAttributeNode("vendedor"),
		    @NamedAttributeNode("ruta"),
		    @NamedAttributeNode("condicionVenta") // si necesitas su nombre
		  }
		)
@Entity
@Table(name = "venta", schema = "dbo")
public class Venta {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumns({ @JoinColumn(name = "codigo_cliente", referencedColumnName = "codigo", nullable = false),
			@JoinColumn(name = "rut_cliente", referencedColumnName = "rut", nullable = false) })
	private Cliente cliente;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumns({ @JoinColumn(name = "codigo_vendedor", referencedColumnName = "codigo", nullable = false),
			@JoinColumn(name = "tipo_vendedor", referencedColumnName = "tipo", nullable = false) })
	private Vendedor vendedor;
	
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "condicion_venta", nullable = false)
	private CondicionVenta condicionVenta;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "codigo_ruta", nullable = false)
	private Ruta ruta;

	@Column(name = "fecha", nullable = false)
	private LocalDate fecha;


	@Column(name = "total_neto", precision = 18, scale = 2, nullable = false)
	private BigDecimal totalNeto;
	
	@Column(name = "total_descuento", precision = 18, scale = 2, nullable = false)
	private BigDecimal totalDescuento;

	@Column(name = "total_iva", precision = 18, scale = 2, nullable = false)
	private BigDecimal totalIva;

	@Column(name = "total_ila", precision = 18, scale = 2, nullable = false)
	private BigDecimal totalIla;

	@Column(name = "total", precision = 18, scale = 2, nullable = false)
	private BigDecimal total;

	@Column(name = "estado", length = 20, nullable = false)
	private String estado = "EMITIDA";

	// ---- Relación con detalles ----
	@OneToMany(mappedBy = "venta", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<VentaDetalle> detalles = new ArrayList<>();

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Cliente getCliente() {
		return cliente;
	}

	public void setCliente(Cliente cliente) {
		this.cliente = cliente;
	}

	public Vendedor getVendedor() {
		return vendedor;
	}

	public void setVendedor(Vendedor vendedor) {
		this.vendedor = vendedor;
	}

	public Ruta getRuta() {
		return ruta;
	}

	public void setRuta(Ruta ruta) {
		this.ruta = ruta;
	}

	public LocalDate getFecha() {
		return fecha;
	}

	public void setFecha(LocalDate fecha) {
		this.fecha = fecha;
	}
	
	

	public BigDecimal getTotalNeto() {
		return totalNeto;
	}

	public void setTotalNeto(BigDecimal totalNeto) {
		this.totalNeto = totalNeto;
	}

	public BigDecimal getTotalDescuento() {
		return totalDescuento;
	}

	public void setTotalDescuento(BigDecimal totalDescuento) {
		this.totalDescuento = totalDescuento;
	}

	public BigDecimal getTotalIva() {
		return totalIva;
	}

	public void setTotalIva(BigDecimal total_iva) {
		this.totalIva = total_iva;
	}

	public BigDecimal getTotalIla() {
		return totalIla;
	}

	public void setTotalIla(BigDecimal total_ila) {
		this.totalIla = total_ila;
	}

	public BigDecimal getTotal() {
		return total;
	}

	public void setTotal(BigDecimal total) {
		this.total = total;
	}

	public String getEstado() {
		return estado;
	}

	public void setEstado(String estado) {
		this.estado = estado;
	}

	public List<VentaDetalle> getDetalles() {
		return detalles;
	}

	public void setDetalles(List<VentaDetalle> detalles) {
		this.detalles = detalles;
	}
	
	public CondicionVenta getCondicionVenta() {
		return condicionVenta;
	}

	public void setCondicionVenta(CondicionVenta condicionVenta) {
		this.condicionVenta = condicionVenta;
	}

	public void addDetalle(VentaDetalle d) {
        d.setVenta(this);
        detalles.add(d);
    }
	
	public void updateDetalle(VentaDetalle d) {
        d.setVenta(this);
	}
	
	public void removeDetalle(VentaDetalle d) {
		detalles.remove(d);
	}
	
	public void removeDetall(int index) {
		detalles.remove(index);
	}
	

}
