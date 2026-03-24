// file: cl/eos/dipalza/model/venta/VentaSummaryDTO.java
package cl.eos.dipalza.model.venta;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

public class VentaSummaryDTO implements Serializable {
	private static final long serialVersionUID = 1L;
	private Long id;
	private LocalDate fecha;
	private String rutCliente;
	private String vendedorCodigo;
	private Integer numeroLineas;
	private Integer piezasTotales;
	private BigDecimal total;

	public VentaSummaryDTO() {
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public LocalDate getFecha() {
		return fecha;
	}

	public void setFecha(LocalDate fecha) {
		this.fecha = fecha;
	}

	public String getRutCliente() {
		return rutCliente;
	}

	public void setRutCliente(String rutCliente) {
		this.rutCliente = rutCliente;
	}

	public String getVendedorCodigo() {
		return vendedorCodigo;
	}

	public void setVendedorCodigo(String vendedorCodigo) {
		this.vendedorCodigo = vendedorCodigo;
	}

	public Integer getNumeroLineas() {
		return numeroLineas;
	}

	public void setNumeroLineas(Integer numeroLineas) {
		this.numeroLineas = numeroLineas;
	}

	public Integer getPiezasTotales() {
		return piezasTotales;
	}

	public void setPiezasTotales(Integer piezasTotales) {
		this.piezasTotales = piezasTotales;
	}

	public BigDecimal getTotal() {
		return total;
	}

	public void setTotal(BigDecimal total) {
		this.total = total;
	}
}
