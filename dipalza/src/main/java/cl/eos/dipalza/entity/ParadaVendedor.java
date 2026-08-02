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
@Table(name = "parada_vendedor", schema = "dbo")
public class ParadaVendedor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
            @JoinColumn(name = "vendedorId", referencedColumnName = "codigo"),
            @JoinColumn(name = "vendedorCodigo", referencedColumnName = "tipo")
    })
    private Vendedor vendedor;

    private double latitud;
    private double longitud;
    private LocalDateTime horaInicio;
    private LocalDateTime horaFin;
    private String calle;
}
