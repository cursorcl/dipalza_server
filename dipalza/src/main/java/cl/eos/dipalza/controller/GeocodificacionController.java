package cl.eos.dipalza.controller;

import cl.eos.dipalza.model.GeocodificacionResponseDTO;
import cl.eos.dipalza.service.GeocodificacionService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/geocodificacion")
@Validated
public class GeocodificacionController {

    private final GeocodificacionService geocodificacionService;

    public GeocodificacionController(GeocodificacionService geocodificacionService) {
        this.geocodificacionService = geocodificacionService;
    }

    @GetMapping("/inversa")
    public ResponseEntity<GeocodificacionResponseDTO> obtenerCalle(
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double lon) {
        double latRedondeada = Math.round(lat * 100000) / 100000.0;
        double lonRedondeada = Math.round(lon * 100000) / 100000.0;
        String calle = geocodificacionService.obtenerCalle(latRedondeada, lonRedondeada);
        return ResponseEntity.ok(new GeocodificacionResponseDTO(calle));
    }
}
