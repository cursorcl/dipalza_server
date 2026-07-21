package cl.eos.dipalza.model;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record PosicionDTO(
        @NotBlank String vendedorId,
        String vendedorCodigo,
        String vendedorNombre,
        @NotNull LocalDateTime fechaHora,
        @DecimalMin("-90.0") @DecimalMax("90.0") double latitud,
        @DecimalMin("-180.0") @DecimalMax("180.0") double longitud) {
}
