package cl.eos.dipalza.model;

public record ActualizarUsuarioDTO(
        String email,
        String codigoVendedor,
        String tipoVendedor,
        boolean enabled,
        boolean locked
) {}
