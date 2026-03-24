package cl.eos.dipalza.entity;

import java.util.Set;

public enum EstadoVenta {

	/** La venta está en proceso, se creó el encabezado y aún se está realizando la venta. */
	OPENED,
	/** El vendedor confirma la venta, queda lista para ser facturada. */
	FINISHED,
	/** Venta que se encuentra facturada, no se debe alterar */
	CLOSED,
	/** La venta fue cancelada, solamente se puede cancelar cuando esté en estado OPENED o FINISHED */
	CANCELED;

	public static EstadoVenta fromName(String estado) {

		if (estado == null || estado.isBlank()) {
			return OPENED;
		}

		try {
			return EstadoVenta.valueOf(estado.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			return OPENED;
		}
	}

	public boolean canTransitionTo(EstadoVenta newState) {
		return switch (this) {
			case OPENED -> Set.of(FINISHED, CANCELED).contains(newState);
			case FINISHED -> Set.of(CLOSED, CANCELED).contains(newState);
			case CLOSED, CANCELED -> false;
		};
	}

}
