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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "numerados")
@Access(AccessType.FIELD)
public class Numerado {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "articulo", nullable = false)
	private Producto producto;

	@Column(name = "numero", nullable = false)
	private Integer numero;

	@Column(name = "peso", precision = 19, scale = 4, nullable = false)
	private BigDecimal peso;

	// 'D' Disponible, 'R' Reservada, 'V' Vendida, 'A' Anulada
	@Column(name = "estado", length = 1, nullable = false)
	private String estado = "D";

	@Column(name = "creado_en", nullable = false)
	private LocalDate creadoEn;

	@Column(name = "actualizado_en", nullable = false)
	private LocalDate actualizadoEn;

	public Numerado() {
	}

	@PrePersist
	public void prePersist() {
		var now = LocalDate.now();
		this.creadoEn = now;
		this.actualizadoEn = now;
	}

	@PreUpdate
	public void preUpdate() {
		this.actualizadoEn = LocalDate.now();
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}


	public Integer getNumero() {
		return numero;
	}

	public void setNumero(Integer numero) {
		this.numero = numero;
	}

	public BigDecimal getPeso() {
		return peso;
	}

	public void setPeso(BigDecimal peso) {
		this.peso = peso;
	}

	public String getEstado() {
		return estado;
	}

	public void setEstado(String estado) {
		this.estado = estado;
	}


	public LocalDate getCreadoEn() {
		return creadoEn;
	}

	public void setCreadoEn(LocalDate creadoEn) {
		this.creadoEn = creadoEn;
	}

	public LocalDate getActualizadoEn() {
		return actualizadoEn;
	}

	public void setActualizadoEn(LocalDate actualizadoEn) {
		this.actualizadoEn = actualizadoEn;
	}

	public Producto getProducto() {
		return producto;
	}

	public void setProducto(Producto producto) {
		this.producto = producto;
	}

}
