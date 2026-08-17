package cl.eos.dipalza.repository;

import cl.eos.dipalza.entity.HistorialPosicion;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface HistorialPosicionRepository extends JpaRepository<HistorialPosicion, String>, JpaSpecificationExecutor<HistorialPosicion> {

    @Override
    @EntityGraph(attributePaths = {"vendedor"})
    List<HistorialPosicion> findAll();

    @Query(value = "SELECT CAST(fechaHora AS date) AS dia, COUNT(*) AS cantidadPuntos, " +
            "MIN(fechaHora) AS horaInicio, MAX(fechaHora) AS horaFin " +
            "FROM dbo.historial_posicion " +
            "WHERE vendedorId = :codigo AND vendedorCodigo = :tipo AND fechaHora >= :desde " +
            "GROUP BY CAST(fechaHora AS date) " +
            "ORDER BY dia DESC", nativeQuery = true)
    List<ResumenDiaProjection> resumenPorDia(@Param("codigo") String codigo, @Param("tipo") String tipo, @Param("desde") LocalDateTime desde);

    interface ResumenDiaProjection {
        LocalDate getDia();
        long getCantidadPuntos();
        LocalDateTime getHoraInicio();
        LocalDateTime getHoraFin();
    }
}
