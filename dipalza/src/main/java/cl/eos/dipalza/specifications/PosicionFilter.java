package cl.eos.dipalza.specifications;

import cl.eos.dipalza.entity.ids.VendedorId;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record PosicionFilter(
        List<VendedorId> vendedorIds,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        LocalDateTime desde,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        LocalDateTime hasta,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate dia
) {
    public boolean tieneFiltroTemporal() {
        return desde != null || hasta != null || dia != null;
    }
}
