package cl.eos.dipalza.service.grabacion;

public record Porcentajes(
        float porcentajeIva,
        float porcentajeIla,
        float porcentajeDescuento
) {

    public float factorDescuento() {
        return 1 - porcentajeDescuento;
    }
}
