package cl.eos.dipalza.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record HistorialResumenDiaDTO(LocalDate dia, long cantidadPuntos, LocalDateTime horaInicio, LocalDateTime horaFin) {}
