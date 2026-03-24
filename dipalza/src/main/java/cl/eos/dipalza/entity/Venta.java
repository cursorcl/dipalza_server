package cl.eos.dipalza.entity;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "venta", schema = "dbo")
public class Venta {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	Long id;

	@ManyToOne(fetch = FetchType.EAGER, optional = false)
	@JoinColumns({ @JoinColumn(name = "codigo_cliente", referencedColumnName = "codigo", nullable = false),
			@JoinColumn(name = "rut_cliente", referencedColumnName = "rut", nullable = false) })
	private Cliente cliente;

	@ManyToOne(fetch = FetchType.EAGER, optional = false)
	@JoinColumns({ @JoinColumn(name = "codigo_vendedor", referencedColumnName = "codigo", nullable = false),
			@JoinColumn(name = "tipo_vendedor", referencedColumnName = "tipo", nullable = false) })
	private Vendedor vendedor;

	@ManyToOne(fetch = FetchType.EAGER, optional = false)
	@JoinColumn(name = "condicion_venta", nullable = false)
	private CondicionVenta condicionVenta;

	@ManyToOne(fetch = FetchType.EAGER, optional = false)
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

	@Enumerated(EnumType.STRING)
	@Column(name = "estado", nullable = false, length = 20)
	private EstadoVenta estado;

	// ---- Relación con detalles ----
	@OneToMany(mappedBy = "venta", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
	@JsonManagedReference
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

	public EstadoVenta getEstado() {
		return estado;
	}

	public void setEstado(EstadoVenta estado) {
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
