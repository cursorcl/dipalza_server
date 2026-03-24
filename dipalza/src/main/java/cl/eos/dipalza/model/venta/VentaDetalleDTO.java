// file: cl/eos/dipalza/model/venta/VentaDetalleCreateDTO.java
package cl.eos.dipalza.model.venta;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

public class VentaDetalleDTO implements Serializable {
	private static final long serialVersionUID = 1L;
	private Long id;
	private Long ventaId;
	private String idProducto; // obligatorio
	private String nombreProducto; // opcional
	private BigDecimal cantidad; // > 0
	private BigDecimal precioUnitario; // ≥ 0
	private BigDecimal porcentajeDescuento; // 0..100 (opcional)
	private BigDecimal porcentajeIva; // 0..100 (obligatorio según reglas)
	private BigDecimal porcentajeIla; // 0..100 (opcional)
	private BigDecimal totalDescuento;
	private BigDecimal totalIva;
	private BigDecimal totalIla;
	private BigDecimal totalLinea;
	private String unidad;
	private BigDecimal piezas; // default 0
	private List<VentaDetallePiezaDTO> piezasDetalle; // opcional

	public VentaDetalleDTO() {
	}
	
	

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Long getVentaId() {
		return ventaId;
	}

	public void setVentaId(Long ventaId) {
		this.ventaId = ventaId;
	}


	public String getIdProducto() {
		return idProducto;
	}

	public void setIdProducto(String productoId) {
		this.idProducto = productoId;
	}

	public String getNombreProducto() {
		return nombreProducto;
	}

	public void setNombreProducto(String descripcion) {
		this.nombreProducto = descripcion;
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

	public BigDecimal getPorcentajeDescuento() {
		return porcentajeDescuento;
	}

	public void setPorcentajeDescuento(BigDecimal porcDescuento) {
		this.porcentajeDescuento = porcDescuento;
	}

	public BigDecimal getPorcentajeIva() {
		return porcentajeIva;
	}

	public void setPorcentajeIva(BigDecimal porcIva) {
		this.porcentajeIva = porcIva;
	}

	public BigDecimal getPorcentajeIla() {
		return porcentajeIla;
	}

	public void setPorcentajeIla(BigDecimal porcIla) {
		this.porcentajeIla = porcIla;
	}

	public BigDecimal getPiezas() {
		return piezas;
	}

	public void setPiezas(BigDecimal piezas) {
		this.piezas = piezas;
	}

	public List<VentaDetallePiezaDTO> getPiezasDetalle() {
		return piezasDetalle;
	}

	public void setPiezasDetalle(List<VentaDetallePiezaDTO> piezasDetalle) {
		this.piezasDetalle = piezasDetalle;
	}

	public BigDecimal getTotalLinea() {
		return totalLinea;
	}

	public void setTotalLinea(BigDecimal totalLinea) {
		this.totalLinea = totalLinea;
	}



	public BigDecimal getTotalIla() {
		return totalIla;
	}



	public void setTotalIla(BigDecimal totalIla) {
		this.totalIla = totalIla;
	}



	public BigDecimal getTotalIva() {
		return totalIva;
	}



	public void setTotalIva(BigDecimal totalIva) {
		this.totalIva = totalIva;
	}



	public BigDecimal getTotalDescuento() {
		return totalDescuento;
	}



	public void setTotalDescuento(BigDecimal totalDescuento) {
		this.totalDescuento = totalDescuento;
	}



	public String getUnidad() {
		return unidad;
	}



	public void setUnidad(String unidad) {
		this.unidad = unidad;
	}
	
	
}
