package cl.eos.dipalza.model;

public record CrearUsuarioResultDTO(
        UsuarioDTO usuario,
        boolean correoEnviado
) {}
