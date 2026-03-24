package cl.eos.dipalza.entity;

import cl.eos.dipalza.entity.ids.VendedorId;
import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Setter
@Getter
@EqualsAndHashCode
@Entity
@Table(name = "posicion", schema = "dbo")
public class Posicion {
    @EmbeddedId
    private VendedorId id;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumns({
            @JoinColumn(name = "vendedorId", referencedColumnName = "codigo"),
            @JoinColumn(name = "vendedorCodigo", referencedColumnName = "tipo")
    })
    private Vendedor vendedor;

    private double latitud;
    private double longitud;

    @Column(name = "ultimaActualizacion")
    private LocalDateTime fechaHora;

}
