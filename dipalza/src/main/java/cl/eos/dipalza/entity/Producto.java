package cl.eos.dipalza.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.SQLRestriction;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "producto", schema = "dbo")
public class Producto {

	@Id
	@Column(name = "Articulo", length = 255, nullable = false)
	private String articulo;

	@Column(name = "Descripcion", length = 200, nullable = false)
	private String descripcion;

	@Column(name = "VentaNeto", columnDefinition = "money", nullable = false)
	private BigDecimal ventaNeto;

	@Column(name = "PorcIla", precision = 5, scale = 2) // ajusta scale si corresponde
	private BigDecimal porcIla;

	@Column(name = "PorcCarne", precision = 5, scale = 2)
	private BigDecimal porcCarne;

	@Column(name = "Unidad", length = 10, nullable = false)
	private String unidad;

	@Column(name = "Stock", columnDefinition = "money")
	private BigDecimal stock;

	// En la tabla se llama "Codigolla" (con doble L)
	@Column(name = "CodigoIla", length = 20)
	private String codigoila;

	@Column(name = "last_update", nullable = false)
	private LocalDate lastUpdate;

	// RowVersion (timestamp/rowversion). Usar para locking optimista.
	@Version
	@Column(name = "rv")
	private byte[] rv;


	private Boolean numbered;

	private BigDecimal pieces;
	// ---- Relación con Numerado ----
	@OneToMany(mappedBy = "producto", cascade = CascadeType.ALL, orphanRemoval = true)
	@SQLRestriction("estado = 'D'")
	private List<Numerado> numerados = new ArrayList<>();
	
	
	public Producto() {
	}

	// Getters y setters
	public String getArticulo() {
		return articulo;
	}

	public void setArticulo(String articulo) {
		this.articulo = articulo;
	}

	public String getDescripcion() {
		return descripcion;
	}

	public void setDescripcion(String descripcion) {
		this.descripcion = descripcion;
	}

	public BigDecimal getVentaNeto() {
		return ventaNeto;
	}

	public void setVentaNeto(BigDecimal ventaNeto) {
		this.ventaNeto = ventaNeto;
	}

	public BigDecimal getPorcIla() {
		return porcIla;
	}

	public void setPorcIla(BigDecimal porcIla) {
		this.porcIla = porcIla;
	}

	public BigDecimal getPorcCarne() {
		return porcCarne;
	}

	public void setPorcCarne(BigDecimal porcCarne) {
		this.porcCarne = porcCarne;
	}

	public String getUnidad() {
		return unidad;
	}

	public void setUnidad(String unidad) {
		this.unidad = unidad;
	}

	public BigDecimal getStock() {
		return stock;
	}

	public void setStock(BigDecimal stock) {
		this.stock = stock;
	}

	public String getCodigoila() {
		return codigoila;
	}

	public void setCodigoila(String codigoila) {
		this.codigoila = codigoila;
	}

	public LocalDate getLastUpdate() {
		return lastUpdate;
	}

	public void setLastUpdate(LocalDate lastUpdate) {
		this.lastUpdate = lastUpdate;
	}

	public byte[] getRv() {
		return rv;
	}

	public void setRv(byte[] rv) {
		this.rv = rv;
	}

	public List<Numerado> getNumerados() {
		return numerados;
	}

	public void setNumerados(List<Numerado> numerados) {
		this.numerados = numerados;
	}

	public Boolean getNumbered() {
		return numbered;
	}

	public void setNumbered(Boolean numbered) {
		this.numbered = numbered;
	}

	public BigDecimal getPieces() {
		return pieces;
	}

	public void setPieces(BigDecimal pieces) {
		this.pieces = pieces;
	}
}
