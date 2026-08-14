package cl.eos.dipalza.model;

public record CrearUsuarioDTO(
        String username,
        String email,
        String codigoVendedor,
        String tipoVendedor,
        String password
) {}
