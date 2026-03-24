package cl.eos.dipalza.model;

import java.time.LocalDateTime;

public record PosicionDTO ( String vendedorId, String vendedorCodigo, String vendedorNombre,  LocalDateTime fechaHora, double latitud, double longitud) {};
