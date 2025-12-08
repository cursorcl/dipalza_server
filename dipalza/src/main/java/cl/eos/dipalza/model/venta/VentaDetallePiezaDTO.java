// file: cl/eos/dipalza/model/venta/VentaDetallePiezaCreateDTO.java
package cl.eos.dipalza.model.venta;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

public class VentaDetallePiezaDTO implements Serializable {
	private static final long serialVersionUID = 1L;
	private Long id; // obligatorio (único global)
	private BigDecimal peso; // opcional
	private Long detalleVentaId;
	private Long inventarioId;
	private LocalDate creadoEn;
	

	public VentaDetallePiezaDTO() {
	}

	public VentaDetallePiezaDTO(Long id, BigDecimal peso) {
		this.id = id;
		this.peso = peso;
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public BigDecimal getPeso() {
		return peso;
	}

	public void setPeso(BigDecimal peso) {
		this.peso = peso;
	}

	public Long getInventarioId() {
		return inventarioId;
	}

	public void setInventarioId(Long invId) {
		this.inventarioId = invId;
	}

	public LocalDate getCreadoEn() {
		return creadoEn;
	}

	public void setCreadoEn(LocalDate creadoEn) {
		this.creadoEn = creadoEn;
	}

	public Long getDetalleVentaId() {
		return detalleVentaId;
	}

	public void setDetalleVentaId(Long detalleVentaId) {
		this.detalleVentaId = detalleVentaId;
	}
	
	
}
