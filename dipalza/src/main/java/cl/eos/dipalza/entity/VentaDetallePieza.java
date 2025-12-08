package cl.eos.dipalza.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "venta_detalle_pieza")
@Access(AccessType.FIELD)
public class VentaDetallePieza {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// Relación con la línea de venta (PK compuesta venta_id + linea)
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "id_detalle_venta", referencedColumnName = "id", nullable = false)
	private VentaDetalle ventaDetalle;

	// Asociación al inventario por pieza (tabla numerados), columna FK inv_id
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "inv_id_pieza", referencedColumnName = "id", unique=true, nullable = false)
	private Numerado numerado;

	@Column(name = "peso", precision = 19, scale = 4, nullable = false)
	private BigDecimal peso;

	@Column(name = "creado_en", nullable = false)
	private LocalDate creadoEn;

	public VentaDetallePieza() {
	}

	@PrePersist
	public void prePersist() {
		if (this.creadoEn == null) {
			this.creadoEn = LocalDate.now(); // SOLO aquí
		}
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public VentaDetalle getVentaDetalle() {
		return ventaDetalle;
	}

	public void setVentaDetalle(VentaDetalle ventaDetalle) {
		this.ventaDetalle = ventaDetalle;
	}

	public BigDecimal getPeso() {
		return peso;
	}

	public void setPeso(BigDecimal peso) {
		this.peso = peso;
	}

	public LocalDate getCreadoEn() {
		return creadoEn;
	}

	public void setCreadoEn(LocalDate creadoEn) {
		this.creadoEn = creadoEn;
	}

	public Numerado getNumerado() {
		return numerado;
	}

	public void setNumerado(Numerado numerado) {
		this.numerado = numerado;
	}

}
