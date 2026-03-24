package cl.eos.dipalza.controller;

import cl.eos.dipalza.entity.EstadoVenta;
import cl.eos.dipalza.entity.Numerado;
import cl.eos.dipalza.entity.Venta;
import cl.eos.dipalza.mapper.VentaMapper;
import cl.eos.dipalza.model.ClienteIdQueryDTO;
import cl.eos.dipalza.model.EstadoVentaDTO;
import cl.eos.dipalza.model.NumeradoDTO;
import cl.eos.dipalza.model.venta.VentaDTO;
import cl.eos.dipalza.model.venta.VentaDetalleDTO;
import cl.eos.dipalza.repository.NumeradoRepository;
import cl.eos.dipalza.service.VentaService;
import cl.eos.dipalza.specifications.VentaFilter;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/ventas")
public class VentaController {

	private final NumeradoRepository numeradoRepository;

	@Autowired
	private VentaService ventaService;

	public VentaController(VentaService ventaService, NumeradoRepository numeradoRepository) {
		this.ventaService = ventaService;
		this.numeradoRepository = numeradoRepository;
	}

	@GetMapping
	public ResponseEntity<List<VentaDTO>> listarVentas(
	    @RequestParam(required = false) List<String> estados,
	    @RequestParam(required = false) List<String> rutsClientes,
	    @RequestParam(required = false) List<String> codigosRutas,
	    @RequestParam(required = false) List<Long> condicionVentaIds,
	    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaInicio,
	    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaFin
	) {
	    VentaFilter filter = new VentaFilter(
	        estados, rutsClientes, codigosRutas, 
	        condicionVentaIds, null, fechaInicio, fechaFin
	    );
	    List<Venta> ventas = ventaService.listarVentas(filter);
	    List<VentaDTO> ventasDTO = ventas.stream().map(VentaMapper::toVentaDTO).toList();
	    return ResponseEntity.ok(ventasDTO);
	}
	
	@PostMapping
	public ResponseEntity<VentaDTO> grabarVenta(@RequestBody VentaDTO venta) {
		VentaDTO response = null;
		if (venta.getId() == null || venta.getId() == -1)
			response = ventaService.crearVenta(venta);
		else
			response = ventaService.actualizarVenta(venta.getId(), venta);

		return new ResponseEntity<>(response, HttpStatus.OK);
	}

	@PostMapping("/encabezado")
	public ResponseEntity<VentaDTO> grabarVentaEncabezado(@RequestBody VentaDTO venta) {
		VentaDTO response = null;
		if (venta.getId() == null || venta.getId() == -1)
			response = ventaService.crearVentaEncabezado(venta);
		else
			response = ventaService.actualizarVentaEncabezado(venta.getId(), venta);

		return new ResponseEntity<>(response, HttpStatus.OK);
	}



	// Eliminar una venta
	@DeleteMapping("/{id}")
	public ResponseEntity<Void> eliminarVenta(@PathVariable Long id) {
		boolean eliminado = ventaService.eliminarVenta(id);
		if (!eliminado) {
			return new ResponseEntity<>(HttpStatus.NOT_FOUND);
		}
		return new ResponseEntity<>(HttpStatus.NO_CONTENT);
	}

	// Obtener todas las ventas de un vendedor en una fecha específica
	@GetMapping("/vendedor/{codigo}/fecha")
	public ResponseEntity<List<VentaDTO>> obtenerVentasPorVendedorYFecha(@PathVariable String codigo,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {

		List<VentaDTO> ventas = ventaService.obtenerVentasPorVendedorYFecha(codigo, fecha);
		return new ResponseEntity<>(ventas, HttpStatus.OK);
	}

	// Obtener la última venta de un cliente
	@PostMapping("/ultimaventacliente")
	public ResponseEntity<VentaDTO> obtenerUltimaVentaDeCliente(@RequestBody ClienteIdQueryDTO params) {
		VentaDTO ultimaVenta = ventaService.obtenerUltimaVentaDeCliente(params);
		if (ultimaVenta == null) {
			return new ResponseEntity<>(HttpStatus.NOT_FOUND);
		}
		return new ResponseEntity<>(ultimaVenta, HttpStatus.OK);
	}

	// Obtener el detalle de una venta.
	@GetMapping("/{idVenta}/detalles")
	public ResponseEntity<List<VentaDetalleDTO>> obtenerTodosLosItemsDelDetalleDeUnaVenta(@PathVariable Long idVenta) {
		return new ResponseEntity<>(ventaService.obtenerDetallePorVenta(idVenta), HttpStatus.OK);
	}

	@DeleteMapping("/eliminarItemVenta/{id}")
	public ResponseEntity<Void> eliminarUnItemDelDetalleDeUnaVenta(@PathVariable Long id) {
		Venta ventaActualizada = ventaService.eliminarItemVenta(id);
		if (ventaActualizada == null) {
			return new ResponseEntity<>(HttpStatus.NOT_FOUND);
		}
		return new ResponseEntity<>(HttpStatus.OK);
	}

	@PostMapping("/detalleVenta")
	public ResponseEntity<VentaDTO> grabarUnItemDelDetalleDeUnaVenta(@RequestBody VentaDetalleDTO ventaDetalleDTO) {
		if (ventaDetalleDTO == null)
			return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
		Venta venta = ventaService.grabarVentaDetalle(ventaDetalleDTO);
		VentaDTO response = VentaMapper.toVentaDTO(venta);
		return new ResponseEntity<>(response, HttpStatus.OK);
	}

	@PutMapping("/updateNumerado")
	public ResponseEntity<NumeradoDTO> actualizarNumerado(@RequestBody NumeradoDTO numeradoDTO) {
		Objects.requireNonNull(numeradoDTO, "El item numerado no puede ser nulo");
		Numerado numerado = this.numeradoRepository.findById(numeradoDTO.getId())
				.orElseThrow((() -> new EntityNotFoundException(
						"El Item Numerado no existe en la base de datos:" + numeradoDTO.getId())));

		numeradoDTO.setActualizadoEn(LocalDate.now());
		numerado.setActualizadoEn(numeradoDTO.getActualizadoEn());
		numerado.setEstado(numeradoDTO.getEstado());
		numeradoRepository.save(numerado);

		return new ResponseEntity<>(numeradoDTO, HttpStatus.OK);

	}

	@PostMapping("/updateEstadoVenta")
	public ResponseEntity<VentaDTO> updateEstadoVenta(@RequestBody EstadoVentaDTO estadoVenta) {
		Objects.requireNonNull(estadoVenta, "El estado de venta debe ser distinto de nulo.");
		Objects.requireNonNull(estadoVenta.idVenta(), "El identificador de venta debe ser distinto de nulo.");
		Objects.requireNonNull(estadoVenta.estadoVenta(), "El estado asociado a la venta debe ser distinto de nulo.");

		EstadoVenta estado = EstadoVenta.fromName(estadoVenta.estadoVenta());
		if (estado == null)
			return new ResponseEntity<>(HttpStatus.BAD_REQUEST);

		Venta venta = this.ventaService.actualizaEstadoVenta(estadoVenta.idVenta(), estado);
		VentaDTO ventaDTO = VentaMapper.toVentaDTO(venta);
		return new ResponseEntity<>(ventaDTO, HttpStatus.OK);
	}
	
	@GetMapping("/fecha")
	public ResponseEntity<List<VentaDTO>> obtenerVentasPorFecha(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {

		List<VentaDTO> ventas = ventaService.obtenerVentasPorFecha(fecha);
		return new ResponseEntity<>(ventas, HttpStatus.OK);
	}

	///  Optimizados
	/**
	 * Búsqueda optimizada: Retorna VentaDTO con detalles en null.
	 */
	@GetMapping("/optimized")
	public ResponseEntity<List<VentaDTO>> listarVentasOptimized(@ModelAttribute VentaFilter filter) {
		List<Venta> ventas = ventaService.listarVentasOptimized(filter);

		// Mapeamos a DTO. El mapper debe estar configurado para
		// no inicializar la colección de detalles si viene Lazy.
		List<VentaDTO> dtos = ventas.stream()
				.map(VentaMapper::toVentaDTO)
				.toList();

		return ResponseEntity.ok(dtos);
	}

	/**
	 * Una sola venta optimizada.
	 */
	@GetMapping("/optimized/{id}")
	public ResponseEntity<VentaDTO> obtenerVentaOptimized(@PathVariable Long id) {
		Venta venta = ventaService.obtenerVentaOptimized(id);
		return ResponseEntity.ok(VentaMapper.toVentaDTO(venta));
	}

	/**
	 * Búsqueda por lotes de IDs optimizada.
	 */
	@PostMapping("/optimized/batch")
	public ResponseEntity<List<VentaDTO>> buscarVentasBatchOptimized(@RequestBody List<Long> ids) {
		List<Venta> ventas = ventaService.findAllByIdInOptimized(ids);
		List<VentaDTO> dtos = ventas.stream()
				.map(VentaMapper::toVentaDTO)
				.toList();
		return ResponseEntity.ok(dtos);
	}
}
