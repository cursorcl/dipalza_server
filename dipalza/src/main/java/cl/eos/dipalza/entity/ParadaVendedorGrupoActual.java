package cl.eos.dipalza.entity;

import cl.eos.dipalza.entity.ids.VendedorId;
import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Setter
@Getter
@EqualsAndHashCode
@Entity
@Table(name = "parada_vendedor_grupo_actual", schema = "dbo")
public class ParadaVendedorGrupoActual {

    @EmbeddedId
    private VendedorId id;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumns({
            @JoinColumn(name = "vendedorId", referencedColumnName = "codigo"),
            @JoinColumn(name = "vendedorCodigo", referencedColumnName = "tipo")
    })
    private Vendedor vendedor;

    private LocalDate dia;
    private double latitudReferencia;
    private double longitudReferencia;
    private LocalDateTime horaInicio;
    private LocalDateTime horaUltimoPunto;
    private double sumaLatitud;
    private double sumaLongitud;
    private int cantidadPuntos;
}
