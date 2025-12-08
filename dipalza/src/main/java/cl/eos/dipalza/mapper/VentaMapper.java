package cl.eos.dipalza.mapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import cl.eos.dipalza.entity.Cliente;
import cl.eos.dipalza.entity.CondicionVenta;
import cl.eos.dipalza.entity.Numerado;
import cl.eos.dipalza.entity.Ruta;
import cl.eos.dipalza.entity.Vendedor;
import cl.eos.dipalza.entity.Venta;
import cl.eos.dipalza.entity.VentaDetalle;
import cl.eos.dipalza.entity.VentaDetallePieza;
import cl.eos.dipalza.model.venta.VentaDTO;
import cl.eos.dipalza.model.venta.VentaDetalleDTO;
import cl.eos.dipalza.model.venta.VentaDetallePiezaDTO;

public class VentaMapper {

	public static VentaDTO toVentaDTO(Venta venta) {
		VentaDTO dto = new VentaDTO();
		dto.setId(venta.getId());
		dto.setFecha(venta.getFecha());

		dto.setRutCliente(venta.getCliente().getId().getRut());
		dto.setCodigoCliente(venta.getCliente().getId().getCodigo());
		dto.setNombreCliente(venta.getCliente().getRazon());

		dto.setCodigoVendedor(venta.getVendedor().getId().getCodigo());
		dto.setTipoVendedor(venta.getVendedor().getId().getTipo());
		dto.setNombreVendedor(venta.getVendedor().getNombre());

		dto.setCodigoRuta(venta.getRuta().getCodigo());
		dto.setNombreRuta(venta.getRuta().getDescripcion());

		dto.setCodigoCondicionVenta(venta.getCondicionVenta().getCodigo());
		dto.setNombreCondicionVenta(venta.getCondicionVenta().getDescripcion());

		// Totales (asumiendo getters en entity)
		dto.setTotalDescuento(nvl(venta.getTotalDescuento()));
		dto.setTotalIva(nvl(venta.getTotalIva()));
		dto.setTotalIla(nvl(venta.getTotalIla()));
		dto.setTotalNeto(nvl(venta.getTotalNeto()));
		dto.setTotal(nvl(venta.getTotal()));

		// Detalles
		if (venta.getDetalles() != null) {
			List<VentaDetalleDTO> dets = venta.getDetalles().stream()
					.sorted(Comparator.comparingLong(VentaDetalle::getId)).map(VentaMapper::toVentaDetalleDTO)
					.collect(Collectors.toList());
			dto.setDetalles(dets);
		} else {
			dto.setDetalles(List.of());
		}
		return dto;
	}

	public static VentaDetalleDTO toVentaDetalleDTO(VentaDetalle d) {

		VentaDetalleDTO dto = new VentaDetalleDTO();

		dto.setId(d.getId());
		dto.setVentaId(d.getVenta().getId());
		dto.setIdProducto(d.getProductoId());
		dto.setNombreProducto(d.getProducto() == null ? "" : d.getProducto().getDescripcion());
		dto.setCantidad(nvl(d.getCantidad()));
		dto.setPrecioUnitario(nvl(d.getPrecioUnitario()));
		dto.setPorcentajeDescuento(nvl(d.getPorcentajeDescuento()));
		dto.setPorcentajeIva(nvl(d.getPorcentajeIva()));
		dto.setPorcentajeIla(nvl(d.getPorcentajeIla()));
		dto.setTotalLinea(nvl(d.getTotalLinea()));
		dto.setTotalDescuento(nvl(d.getTotalDescuento()));
		dto.setTotalIla(nvl(d.getTotalIla()));
		dto.setTotalIva(nvl(d.getTotalIva()));
		dto.setUnidad(d.getUnidad());
		dto.setPiezas(d.getPiezas());
		// Mapear las piezas numeradas asociadas a este detalle
		if (d.getPiezasUsadas() != null && !d.getPiezasUsadas().isEmpty()) {
			List<VentaDetallePiezaDTO> piezasDto = d.getPiezasUsadas().stream().map(VentaMapper::toVentaDetaalleiezaDTO) // Mapeamos
																															// cada
																															// pieza
																															// numerada
					.collect(Collectors.toList());
			dto.setPiezasDetalle(piezasDto); // Establecemos las piezas en el DTO
		} else {
			dto.setPiezasDetalle(List.of()); // Si no hay piezas, asignamos una lista vacía
		}

		return dto;
	}

	public static VentaDetallePiezaDTO toVentaDetaalleiezaDTO(VentaDetallePieza pieza) {
		VentaDetallePiezaDTO piezaDto = new VentaDetallePiezaDTO();
		piezaDto.setId(pieza.getId()); // Asumimos que invId es el identificador de la pieza
		piezaDto.setPeso(nvl(pieza.getPeso())); // El peso es opcional
		piezaDto.setCreadoEn(pieza.getCreadoEn() != null ? pieza.getCreadoEn() : LocalDate.now());
		piezaDto.setDetalleVentaId(pieza.getVentaDetalle().getId());
		piezaDto.setInventarioId(pieza.getNumerado().getId());

		return piezaDto;
	}

	private static BigDecimal nvl(BigDecimal x) {
		return x == null ? BigDecimal.ZERO : x;
	}

	// Mapeo de VentaCreateDTO a Venta
	public static Venta toVentaEntity(VentaDTO dto, Cliente cliente, Vendedor vendedor, Ruta ruta, CondicionVenta condicionVenta) {

		Venta venta = new Venta();
		// Mapear propiedades de la cabecera de la venta
		venta.setFecha(dto.getFecha());
		venta.setCliente(cliente);
		venta.setVendedor(vendedor);
		venta.setRuta(ruta);
		venta.setCondicionVenta(condicionVenta);
		venta.setEstado("CREADA");
		venta.setTotalNeto(BigDecimal.ZERO);
		venta.setTotal(BigDecimal.ZERO);
		venta.setTotalDescuento(BigDecimal.ZERO);
		venta.setTotalIla(BigDecimal.ZERO);
		venta.setTotalIva(BigDecimal.ZERO);

		if (dto.getDetalles() != null) {
			List<VentaDetalle> detalles = dto.getDetalles().stream()
					.map((VentaDetalleDTO detalleDto) -> toVentaDetalleEntity(detalleDto, venta))
					.collect(Collectors.toList());
			venta.setDetalles(detalles);
		}

		return venta;
	}

	// Mapeo de VentaDetalleCreateDTO a VentaDetalle
	public static VentaDetalle toVentaDetalleEntity(VentaDetalleDTO dto, Venta venta) {

		VentaDetalle detalle = new VentaDetalle();
		if (dto.getId() == null || dto.getId() == -1) {
			detalle.setId(null); // <- MUY IMPORTANTE
		} else {
			detalle.setId(dto.getId());
		}

		detalle.setProductoId(dto.getIdProducto());
		detalle.setVenta(venta);
		detalle.setCantidad(dto.getCantidad());
		detalle.setPrecioUnitario(dto.getPrecioUnitario());
		detalle.setPorcentajeDescuento(dto.getPorcentajeDescuento());
		detalle.setPorcentajeIva(dto.getPorcentajeIva());
		detalle.setPorcentajeIla(dto.getPorcentajeIla());
		detalle.setTotalDescuento(dto.getTotalDescuento());
		detalle.setTotalIla(dto.getTotalIla());
		detalle.setTotalIva(dto.getTotalIva());
		detalle.setTotalLinea(dto.getTotalLinea());
		detalle.setUnidad(dto.getUnidad());
		detalle.setPiezas(dto.getPiezas());

		// Mapear piezas numeradas si vienen en el DTO
		if (dto.getPiezasDetalle() != null) {
			List<VentaDetallePieza> piezas = dto.getPiezasDetalle().stream().map(p -> {
				VentaDetallePieza pieza = new VentaDetallePieza();

				if (p.getId() == null || p.getId().longValue() == -1)
					pieza.setId(null); // <- SIEMPRE null para nuevas
				else
					pieza.setId(p.getId());

				pieza.setVentaDetalle(detalle); // <- RELACIÓN
				Numerado numerado =  new Numerado();
				numerado.setId(p.getInventarioId());
				pieza.setNumerado(numerado);
				pieza.setPeso(p.getPeso());

				return pieza;
			}).toList();

			detalle.setPiezasUsadas(piezas);
		}

		return detalle;
	}

	// Mapeo de VentaDetallePiezaCreateDTO a VentaDetallePieza
	public static VentaDetallePieza toVentaDetallePiezaEntity(VentaDetallePiezaDTO dto) {
		VentaDetallePieza pieza = new VentaDetallePieza();

		pieza.setId(dto.getId()); // invId debe ser único para cada pieza
		pieza.setPeso(dto.getPeso());
		pieza.setCreadoEn(LocalDate.now());

		return pieza;
	}
}
