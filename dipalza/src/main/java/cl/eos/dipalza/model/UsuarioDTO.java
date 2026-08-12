package cl.eos.dipalza.model;

import java.time.LocalDate;

public record UsuarioDTO(
        Long id,
        String username,
        String email,
        String codigoVendedor,
        String tipoVendedor,
        String nombreVendedor,
        boolean enabled,
        boolean locked,
        LocalDate createdAt
) {}
