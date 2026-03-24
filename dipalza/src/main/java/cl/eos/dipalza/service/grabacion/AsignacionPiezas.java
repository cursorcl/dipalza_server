package cl.eos.dipalza.service.grabacion;

public record AsignacionPiezas(
        float cantidadPiezasDisponibles,
        int cantidadPiezasSolicitadas,
        float diferenciaPiezas,
        float cantidadPiezasAsignada
) {
}