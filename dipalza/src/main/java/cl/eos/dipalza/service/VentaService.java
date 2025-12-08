
package cl.eos.dipalza.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import cl.eos.dipalza.entity.Cliente;
import cl.eos.dipalza.entity.CondicionVenta;
import cl.eos.dipalza.entity.Numerado;
import cl.eos.dipalza.entity.Ruta;
import cl.eos.dipalza.entity.Vendedor;
import cl.eos.dipalza.entity.Venta;
import cl.eos.dipalza.entity.VentaDetalle;
import cl.eos.dipalza.entity.VentaDetallePieza;
import cl.eos.dipalza.entity.ids.ClienteId;
import cl.eos.dipalza.entity.ids.VendedorId;
import cl.eos.dipalza.exceptions.MissingDataException;
import cl.eos.dipalza.mapper.VentaMapper;
import cl.eos.dipalza.model.venta.VentaDTO;
import cl.eos.dipalza.model.venta.VentaDetalleDTO;
import cl.eos.dipalza.model.venta.VentaDetallePiezaDTO;
import cl.eos.dipalza.repository.ClienteRepository;
import cl.eos.dipalza.repository.CondicionVentaRepository;
import cl.eos.dipalza.repository.NumeradoRepository;
import cl.eos.dipalza.repository.ProductoRepository;
import cl.eos.dipalza.repository.RutaRepository;
import cl.eos.dipalza.repository.VendedorRepository;
import cl.eos.dipalza.repository.VentaDetallePiezaRepository;
import cl.eos.dipalza.repository.VentaDetalleRepository;
import cl.eos.dipalza.repository.VentaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

@Service
@Transactional
public class VentaService {

    private final VendedorRepository vendedorRepository;

	private final CondicionVentaRepository condicionVentaRepository;

	private final RutaRepository rutaRepository;

	private final ClienteRepository clienteRepository;

	private final NumeradoRepository numeradoRepository;

	// inyección gestionada por Spring
	@PersistenceContext
	private EntityManager em;

	private final VentaRepository ventaRepository;
	private final VentaDetalleRepository ventaDetalleRepository;
	private final VentaDetallePiezaRepository ventaDetallePiezaRepository;

	public VentaService(VentaRepository ventaRepository, VentaDetalleRepository ventaDetalleRepository,
			VentaDetallePiezaRepository ventaDetallePiezaRepository, ProductoRepository productoRepository,
			NumeradoRepository numeradoRepository, ClienteRepository clienteRepository, RutaRepository rutaRepository,
			VendedorRepository vendedorRepository,
			CondicionVentaRepository condicionVentaRepository) {
		this.ventaRepository = ventaRepository;
		this.ventaDetalleRepository = ventaDetalleRepository;
		this.ventaDetallePiezaRepository = ventaDetallePiezaRepository;
		this.numeradoRepository = numeradoRepository;
		this.clienteRepository = clienteRepository;
		this.rutaRepository = rutaRepository;
		this.condicionVentaRepository = condicionVentaRepository;
		this.vendedorRepository = vendedorRepository;
	}

	public VentaDTO crearVenta(VentaDTO dto) {

		if (dto.getCodigoCliente() == null)
			throw new MissingDataException("Falta el código de cliente!");
		if (dto.getRutCliente() == null || dto.getRutCliente().isBlank())
			throw new MissingDataException("Falta el rut del cliente!");

		if (dto.getCodigoVendedor() == null || dto.getCodigoVendedor().isBlank())
			throw new MissingDataException("Falta el código de vendedor!");

		if (dto.getTipoVendedor() == null || dto.getTipoVendedor().isBlank())
			throw new MissingDataException("Falta el tipo de vendedor!");

		if (dto.getCodigoRuta() == null || dto.getCodigoRuta().isBlank())
			throw new MissingDataException("Falta el código de ruta!");

		if (dto.getCodigoCondicionVenta() == null || dto.getCodigoCondicionVenta().isBlank())
			throw new MissingDataException("Falta la condición de venta!");

		Optional<Cliente> cliente = this.clienteRepository
				.findById(new ClienteId(dto.getRutCliente(), dto.getCodigoCliente()));
		if (cliente.isEmpty())
			throw new EntityNotFoundException("El cliente especificado no existe!!");

		Optional<Vendedor> vendedor = this.vendedorRepository.findById(new VendedorId(dto.getCodigoVendedor(), dto.getTipoVendedor()));
		if(vendedor.isEmpty())
			throw new EntityNotFoundException("El vendedor especificado no existe!!");
		

		Optional<Ruta> ruta = this.rutaRepository.findById(dto.getCodigoRuta());
		if(ruta.isEmpty())
			throw new EntityNotFoundException("La ruta especificada no existe!!");

		Optional<CondicionVenta> condicionVenta = this.condicionVentaRepository.findById(dto.getCodigoCondicionVenta());
		if(condicionVenta.isEmpty())
			throw new EntityNotFoundException("La condición de venta especificada no existe!!");

		// Usando el mapper para convertir DTO a entidad
		Venta venta = VentaMapper.toVentaEntity(dto, cliente.get(), vendedor.get(), ruta.get(), condicionVenta.get());

		// Guardar la venta en la base de datos (esto asigna un id a la venta)
		venta = ventaRepository.save(venta);

		// En la creación, puede venir sin los registros de venta.
		if (dto.getDetalles() != null && !dto.getDetalles().isEmpty()) {
			// Luego, los detalles de la venta se guardan con el id generado
			for (VentaDetalle detalle : venta.getDetalles()) {
				detalle.setVenta(venta); // Establecemos la relación de la venta con cada detalle
			}

			// Guardar los detalles en la base de datos (debe estar relacionado con la
			// venta)
			for (VentaDetalle detalle : venta.getDetalles()) {
				// Guardar cada detalle de venta
				ventaDetalleRepository.save(detalle);

				// Guardar las piezas asociadas a ese detalle
				if (detalle.getPiezasUsadas() != null) {
					for (VentaDetallePieza pieza : detalle.getPiezasUsadas()) {
						pieza.setVentaDetalle(detalle); // Asignar la relación entre pieza y detalle

						ventaDetallePiezaRepository.save(pieza); // Guardar la pieza

						Optional<Numerado> numerado = this.numeradoRepository.findById(pieza.getNumerado().getId());
						if (numerado.isPresent()) {
							numerado.get().setEstado("V");
							this.numeradoRepository.save(numerado.get());
						}
					}
				}
			}
		}

		// Retornar el DTO de respuesta
		return VentaMapper.toVentaDTO(venta);
	}

	public VentaDTO actualizarVenta(Long id, VentaDTO dto) {
		Objects.requireNonNull(dto, "dto no puede ser null");
		Objects.requireNonNull(id, "id no puede ser null");

		Venta venta = ventaRepository.findById(id)
				.orElseThrow(() -> new EntityNotFoundException("Venta no encontrada: " + id));

		// Cabecera
		if (dto.getFecha() != null)
			venta.setFecha(dto.getFecha());

		if (dto.getRutCliente() != null && dto.getCodigoCliente() != null) {
			ClienteId clienteId = new ClienteId(dto.getRutCliente(), dto.getCodigoCliente());
			Cliente cliente = em.getReference(Cliente.class, clienteId);
			venta.setCliente(cliente);
		}
		if (dto.getCodigoVendedor() != null && dto.getTipoVendedor() != null) {
			VendedorId vendedorId = new VendedorId(dto.getCodigoVendedor(), dto.getTipoVendedor());
			Vendedor vendedor = em.getReference(Vendedor.class, vendedorId);
			venta.setVendedor(vendedor);
		}
		if (dto.getCodigoRuta() != null) {
			Ruta ruta = em.getReference(Ruta.class, dto.getCodigoRuta());
			venta.setRuta(ruta);
		}

		syncDetalles(venta, dto.getDetalles());

		// Recalcular totales cabecera
		recalcularTotalesVenta(venta);

		venta = ventaRepository.save(venta);
		return VentaMapper.toVentaDTO(venta);
	}

	private void syncDetalles(Venta venta, List<VentaDetalleDTO> detallesDto) {
		Map<Long, VentaDetalle> existentes = venta.getDetalles().stream()
				.collect(Collectors.toMap(VentaDetalle::getId, Function.identity()));

		List<VentaDetalle> nuevos = new ArrayList<>();
		if (detallesDto != null) {
			for (VentaDetalleDTO dto : detallesDto) {
				VentaDetalle detalle = existentes.remove(dto.getId());

				if (detalle == null) {
					// Nuevo
					detalle = new VentaDetalle();
					detalle.setVenta(venta);
				}

				// Campos editables
				detalle.setCantidad(dto.getCantidad());
				detalle.setPrecioUnitario(dto.getPrecioUnitario());
				detalle.setTotalLinea(dto.getTotalLinea());
				detalle.setPorcentajeDescuento(dto.getPorcentajeDescuento());
				detalle.setPorcentajeIla(dto.getPorcentajeIla());
				detalle.setPorcentajeIva(dto.getPorcentajeIva());

				// Sincroniza las piezas
				syncPiezas(detalle, dto.getPiezasDetalle());

				nuevos.add(detalle);
			}

			// Los que quedaron en el mapa no están en el DTO → eliminar
			existentes.values().forEach(venta.getDetalles()::remove);
		}
		// Reemplaza por la nueva lista sincronizada
		venta.getDetalles().clear();
		venta.getDetalles().addAll(nuevos);
	}

	private void syncPiezas(VentaDetalle detalle, List<VentaDetallePiezaDTO> piezasDto) {
		// 1. Obtener la referencia a la lista VIGILADA por Hibernate
		// NO crear una lista nueva tipo 'List<...> nuevas = new ArrayList<>()' para
		// reemplazarla después.
		List<VentaDetallePieza> piezasActuales = detalle.getPiezasUsadas();

		if (piezasDto == null || piezasDto.isEmpty()) {
			piezasActuales.clear();
			return;
		}

		// 2. Mapa de las piezas que ya existen en base de datos (para actualizar y no
		// borrar/recrear)
		Map<Long, VentaDetallePieza> existentes = piezasActuales.stream().filter(p -> p.getId() != null)
				.collect(Collectors.toMap(VentaDetallePieza::getId, Function.identity()));

		// 3. Set de IDs que vienen del front (para saber cuáles borrar)
		Set<Long> idsEnDto = piezasDto.stream().map(VentaDetallePiezaDTO::getId).filter(Objects::nonNull)
				.collect(Collectors.toSet());

		// A. Eliminar (Remove)
		// Primero rescato los id de inventario de las piezas que debo eliminar
		// para poder cambiar su estado a disponible
		List<Long> idsNumerados = piezasActuales.stream()
				.filter(p -> p.getId() != null && !idsEnDto.contains(p.getId())).map(p -> p.getNumerado().getId())
				.toList();
		// Eliminamos de la lista 'piezasActuales' lo que no viene en el DTO.
		// Al hacer esto sobre la lista gestionada, Hibernate disparará el DELETE en la
		// BD.
		piezasActuales.removeIf(p -> p.getId() != null && !idsEnDto.contains(p.getId()));

		// Cambio el estado de todos los numerados
		for (Long id : idsNumerados) {
			Optional<Numerado> numerado = this.numeradoRepository.findById(id);
			if (numerado.isEmpty())
				continue;
			numerado.get().setEstado("D");
			this.numeradoRepository.save(numerado.get());
		}

		// B. Agregar o Actualizar
		for (VentaDetallePiezaDTO dto : piezasDto) {
			if (dto.getId() != null && existentes.containsKey(dto.getId())) {
				// --- ACTUALIZAR EXISTENTE ---
				VentaDetallePieza pieza = existentes.get(dto.getId());
				// Actualizas los campos simples
				pieza.setPeso(dto.getPeso());
				// No tocamos la relación 'ventaDetalle' porque ya la tiene

				// Ojo: Si cambia el 'Numerado' (invId), actualízalo
				if (dto.getInventarioId() != null && (pieza.getNumerado() == null
						|| !pieza.getNumerado().getId().equals(dto.getInventarioId()))) {
					Numerado numerado = em.getReference(Numerado.class, dto.getInventarioId());
					pieza.setNumerado(numerado);
				}

			} else {
				// --- AGREGAR NUEVO ---
				VentaDetallePieza nuevaPieza = new VentaDetallePieza();
				nuevaPieza.setVentaDetalle(detalle); // Vincular al padre
				nuevaPieza.setPeso(dto.getPeso());
				nuevaPieza.setCreadoEn(dto.getCreadoEn() != null ? dto.getCreadoEn() : LocalDate.now());
				if (dto.getInventarioId() != null) {
					Numerado numerado = em.getReference(Numerado.class, dto.getInventarioId());
					nuevaPieza.setNumerado(numerado);
				}

				// Agregamos directamente a la lista de Hibernate
				piezasActuales.add(nuevaPieza);
			}
		}

		// NO HACER ESTO JAMÁS:
		// detalle.setPiezasUsadas(nuevas);
	}

	public boolean eliminarVenta(Long id) {
		Objects.requireNonNull(id, "id no puede ser null");

		Venta venta = ventaRepository.findById(id)
				.orElseThrow(() -> new EntityNotFoundException("Venta no encontrada: " + id));

		// Si requiere borrado lógico, marque estado/flag en lugar de delete.
		ventaRepository.delete(venta);

		return true;
	}

	// Obtener todas las ventas de un vendedor en una fecha específica
	public List<VentaDTO> obtenerVentasPorVendedorYFecha(String vendedorCodigo, LocalDate fecha) {
		List<Venta> ventas = ventaRepository.findVentasByVendedorAndFecha(vendedorCodigo, fecha);

		return ventas.stream().map(v -> VentaMapper.toVentaDTO(v)).toList();
	}

	// Obtener la última venta de un cliente
	public VentaDTO obtenerUltimaVentaDeCliente(String rutCliente) {
		Venta ultimaVenta = ventaRepository.findLastVentaByCliente(rutCliente);

		return ultimaVenta == null ? null : VentaMapper.toVentaDTO(ultimaVenta);
	}

	public List<VentaDetalleDTO> obtenerDetallePorVenta(Long ventaId) {
		List<VentaDetalle> detalles = ventaDetalleRepository.findByVentaId(ventaId);
		return detalles.stream().map(d -> VentaMapper.toVentaDetalleDTO(d)).toList();
	}

	private static BigDecimal nvl(BigDecimal x) {
		return x == null ? BigDecimal.ZERO : x;
	}
	// =========================
	// Cálculos de totales
	// =========================

	private void calcularTotalesLinea(VentaDetalle d) {
		BigDecimal cant = nvl(d.getCantidad());
		BigDecimal pu = nvl(d.getPrecioUnitario());
		BigDecimal pDesc = pct(nvl(d.getPorcentajeDescuento()));
		BigDecimal pIva = pct(nvl(d.getPorcentajeIva()));
		BigDecimal pIla = pct(nvl(d.getPorcentajeIla()));

		BigDecimal bruto = pu.multiply(cant); // PU * cant
		BigDecimal desc = bruto.multiply(pDesc); // descuento
		BigDecimal totlaLinea = bruto.subtract(desc); // neto
		BigDecimal ila = totlaLinea.multiply(pIla); // ILA sobre neto (ajuste si su regla difiere)
		BigDecimal iva = totlaLinea.multiply(pIva); // IVA

		d.setTotalDescuento(scale(desc));
		d.setTotalIla(scale(ila));
		d.setTotalIva(scale(iva));
		d.setTotalLinea(scale(totlaLinea));
	}

	private void recalcularTotalesVenta(Venta v) {
		BigDecimal desc = BigDecimal.ZERO;
		BigDecimal iva = BigDecimal.ZERO;
		BigDecimal ila = BigDecimal.ZERO;
		BigDecimal total = BigDecimal.ZERO;

		if (v.getDetalles() != null) {
			for (VentaDetalle d : v.getDetalles()) {
				calcularTotalesLinea(d); // garantizar consistencia
				desc = desc.add(nvl(d.getTotalDescuento()));
				iva = iva.add(nvl(d.getTotalIva()));
				ila = ila.add(nvl(d.getTotalIla()));
				total = total.add(nvl(d.getTotalLinea()));
			}
		}

		// Asuma setters en entity Venta:
		v.setTotalDescuento(scale(desc));
		v.setTotalIva(scale(iva));
		v.setTotalIla(scale(ila));
		v.setTotal(scale(total));
	}

	private static BigDecimal pct(BigDecimal porcentaje0a100) {
		// 19 -> 0.19
		if (porcentaje0a100.compareTo(BigDecimal.ZERO) <= 0)
			return BigDecimal.ZERO;
		return porcentaje0a100.movePointLeft(2);
	}

	@SuppressWarnings("deprecation")
	private static BigDecimal scale(BigDecimal x) {
		// Ajuste a la escala que maneje su BD (p.ej., money 4 decimales)
		return x.setScale(4, BigDecimal.ROUND_HALF_UP);
	}

	// ===============================
	// DETALLES
	// ===============================
	public Venta eliminarItemVenta(Long id) {
		Objects.requireNonNull(id, "id no puede ser null");

		VentaDetalle ventaDetalle = ventaDetalleRepository.findById(id)
				.orElseThrow(() -> new EntityNotFoundException("Item de Venta no encontrado: " + id));

		Venta venta = ventaDetalle.getVenta();

		venta.removeDetalle(ventaDetalle);
		recalcularTotalesVenta(venta);

		ventaRepository.save(venta);

		return venta;
	}

	public Venta grabarVentaDetalle(VentaDetalleDTO ventaDetalleDTO) {
		Objects.requireNonNull(ventaDetalleDTO, "ventaDetalle no puede ser null");
		Objects.requireNonNull(ventaDetalleDTO.getVentaId(), "El Identificador de la venta no puede ser null");

		Venta venta = ventaRepository.findById(ventaDetalleDTO.getVentaId())
				.orElseThrow(() -> new EntityNotFoundException("Venta no encontrada: " + ventaDetalleDTO.getVentaId()));

		final VentaDetalle ventaDetalle = VentaMapper.toVentaDetalleEntity(ventaDetalleDTO, venta);

		// Es nueva
		if (ventaDetalleDTO.getId() == null || ventaDetalleDTO.getId().longValue() == -1) {

			venta.getDetalles().add(ventaDetalle);

		} else {

			VentaDetalle old = venta.getDetalles().stream().filter(d -> d.getId().equals(ventaDetalleDTO.getId()))
					.findFirst().orElseThrow(() -> new EntityNotFoundException(
							"Item de Venta no encontrado en la venta actual: " + ventaDetalleDTO.getId()));

			old.setProductoId(ventaDetalle.getProductoId());
			old.setCantidad(ventaDetalle.getCantidad());
			old.setPrecioUnitario(ventaDetalle.getPrecioUnitario());
			old.setTotalLinea(ventaDetalle.getTotalLinea());
			old.setPorcentajeDescuento(ventaDetalle.getPorcentajeDescuento());
			old.setTotalDescuento(ventaDetalle.getTotalDescuento());
			old.setPorcentajeIla(ventaDetalle.getPorcentajeIla());
			old.setTotalIla(ventaDetalle.getTotalIla());
			old.setPorcentajeIva(ventaDetalle.getPorcentajeIva());
			old.setTotalIva(ventaDetalle.getTotalIva());
			old.setPiezas(ventaDetalle.getPiezas());
			old.setUnidad(ventaDetalle.getUnidad());

			syncPiezas(old, ventaDetalleDTO.getPiezasDetalle());
		}
		// Recalcular totales cabecera
		recalcularTotalesVenta(venta);

		venta = ventaRepository.save(venta);

		VentaDetalle vdetalle = venta.getDetalles().stream()
				.filter(d -> d.getProductoId().equals(ventaDetalle.getProductoId())).findFirst()
				.orElseThrow(() -> new IllegalStateException("No se pudo obtener el detalle recién guardado"));

		if (vdetalle.getPiezasUsadas() != null) {
			for (VentaDetallePieza pieza : vdetalle.getPiezasUsadas()) {
				pieza.setVentaDetalle(vdetalle); // Asignar la relación entre pieza y detalle
				ventaDetallePiezaRepository.save(pieza); // Guardar la pieza
				Optional<Numerado> numerado = this.numeradoRepository.findById(pieza.getNumerado().getId());
				if (numerado.isPresent()) {
					numerado.get().setEstado("V");
					this.numeradoRepository.save(numerado.get());
				}
			}
		}

		return venta;

	}

}
