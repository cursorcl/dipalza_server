package cl.eos.dipalza.entity;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Setter
@Getter
@EqualsAndHashCode
@Entity
@Table(name = "historial_posicion", schema = "dbo")
public class HistorialPosicion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
            @JoinColumn(name = "vendedorId", referencedColumnName = "codigo"),
            @JoinColumn(name = "vendedorCodigo", referencedColumnName = "tipo")
    })
    private Vendedor vendedor;

    private LocalDateTime fechaHora;
    private double  latitud;
    private double  longitud;


}
