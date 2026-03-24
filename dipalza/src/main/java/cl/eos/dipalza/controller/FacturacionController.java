package cl.eos.dipalza.controller;

import cl.eos.dipalza.service.FacturacionService;
import cl.eos.dipalza.service.resultados.VentaFacturaResultado;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/facturacion")
public class FacturacionController {

    private final FacturacionService facturacionService;

    public FacturacionController(FacturacionService facturacionService) {
        this.facturacionService =  facturacionService;
    }
    @PostMapping
    public ResponseEntity<List<VentaFacturaResultado>> facturarVentas() {
        List<VentaFacturaResultado> response = facturacionService.facturar();
        if(response == null || response.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NO_CONTENT);
        }
        return new ResponseEntity<List<VentaFacturaResultado>>(response, HttpStatus.OK);
    }
	
}
