package cl.eos.dipalza.specifications;

import cl.eos.dipalza.entity.HistorialPosicion;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

public class HistorialPosicionSpecifications {

    public static Specification<HistorialPosicion> conFiltros(PosicionFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (filter.vendedorIds() != null && !filter.vendedorIds().isEmpty()) {
                // Navegamos: HistorialPosicion -> Vendedor -> VendedorId
                // Esto permite que JPA use el par (codigo, tipo) en la consulta SQL
                predicates.add(root.get("vendedor").get("id").in(filter.vendedorIds()));
            }

            if (filter.desde() != null && filter.hasta() != null) {
                predicates.add(cb.between(root.get("fechaHora"), filter.desde(), filter.hasta()));
            } else if (filter.dia() != null) {
                predicates.add(cb.between(root.get("fechaHora"),
                        filter.dia().atStartOfDay(),
                        filter.dia().atTime(LocalTime.MAX)));
            }

            query.orderBy(cb.asc(root.get("fechaHora")));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}