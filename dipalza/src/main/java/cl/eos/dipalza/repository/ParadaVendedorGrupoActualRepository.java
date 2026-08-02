package cl.eos.dipalza.repository;

import cl.eos.dipalza.entity.ParadaVendedorGrupoActual;
import cl.eos.dipalza.entity.ids.VendedorId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParadaVendedorGrupoActualRepository
        extends JpaRepository<ParadaVendedorGrupoActual, VendedorId> {
}
