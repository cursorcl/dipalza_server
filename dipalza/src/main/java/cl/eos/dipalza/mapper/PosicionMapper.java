package cl.eos.dipalza.mapper;

import cl.eos.dipalza.entity.HistorialPosicion;
import cl.eos.dipalza.entity.Posicion;
import cl.eos.dipalza.model.HistorialPosicionDTO;
import cl.eos.dipalza.model.PosicionDTO;

/**
 * Clase utilitaria que permite realizar la conversión en ambos sentidos de:
 * - Posicion <--> PosicionDTO
 * - HistorialPosicion <--> HistorialPosicionDTO
 *
 * Estas clases se usan para registrar la posición reportada por la aplicación movil.
 */
public class PosicionMapper {

    public static HistorialPosicionDTO toHistorialDTO(HistorialPosicion posicion) {

        if(posicion == null || posicion.getVendedor() == null) {
            return null;
        }
        return new HistorialPosicionDTO(
                posicion.getId(), posicion.getVendedor().getId().getCodigo(), posicion.getVendedor().getId().getTipo(), posicion.getVendedor().getNombre(), posicion.getFechaHora(), posicion.getLatitud(), posicion.getLongitud());
    }

    public static PosicionDTO toPosicionDTO(Posicion posicion) {

        // Acceso seguro: Posicion -> Vendedor -> Nombre
        String nombre = (posicion.getVendedor() != null)
                ? posicion.getVendedor().getNombre()
                : "Desconocido";

        return new PosicionDTO(
                posicion.getId().getCodigo(),
                posicion.getId().getTipo(),
                nombre,
                posicion.getFechaHora(),
                posicion.getLatitud(),
                posicion.getLongitud());
    }

}
