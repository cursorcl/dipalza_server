package cl.eos.dipalza.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cl.eos.dipalza.entity.Numerado;
import cl.eos.dipalza.entity.Venta;
import cl.eos.dipalza.mapper.VentaMapper;
import cl.eos.dipalza.model.NumeradoDTO;
import cl.eos.dipalza.model.venta.VentaDTO;
import cl.eos.dipalza.model.venta.VentaDetalleDTO;
import cl.eos.dipalza.repository.NumeradoRepository;
import cl.eos.dipalza.service.VentaService;
import jakarta.persistence.EntityNotFoundException;

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

	@PostMapping
	public ResponseEntity<VentaDTO> grabarVenta(@RequestBody VentaDTO venta) {
		VentaDTO response = null;
		if (venta.getId() == null || venta.getId().longValue() == -1)
			response = ventaService.crearVenta(venta);
		else
			response = ventaService.actualizarVenta(venta.getId(), venta);

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
	@GetMapping("/cliente/{rut}")
	public ResponseEntity<VentaDTO> obtenerUltimaVentaDeCliente(@PathVariable String rut) {
		VentaDTO ultimaVenta = ventaService.obtenerUltimaVentaDeCliente(rut);
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
}
