package cl.eos.dipalza.model;

import java.time.LocalDateTime;

public record ParadaVendedorDTO(
        Long id, String vendedorId, String vendedorCodigo, String vendedorNombre,
        double latitud, double longitud, String calle,
        LocalDateTime horaInicio, LocalDateTime horaFin) {
}
