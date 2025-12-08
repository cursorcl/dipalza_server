package cl.eos.dipalza.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public class NumeradoDTO {

	private Long id;

	private String articulo;

	private Integer numero;

	private BigDecimal peso;

	// 'D' Disponible, 'R' Reservada, 'V' Vendida, 'A' Anulada
	private String estado = "D";

	private LocalDate creadoEn;

	private LocalDate actualizadoEn;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getArticulo() {
		return articulo;
	}

	public void setArticulo(String productoId) {
		this.articulo = productoId;
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

}
