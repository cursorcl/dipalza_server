package cl.eos.dipalza.exceptions;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Expone el mensaje de negocio de las {@link ResponseStatusException} lanzadas
 * explícitamente por los servicios (validaciones controladas, con mensajes
 * pensados para mostrarse al usuario). Las excepciones no controladas (500)
 * no pasan por acá y siguen sin exponer detalle, que es el comportamiento
 * por defecto de Spring Boot.
 */
@RestControllerAdvice
public class ResponseStatusExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatusException(ResponseStatusException ex) {
        return ResponseEntity
                .status(ex.getStatusCode())
                .body(Map.of("message", ex.getReason()));
    }
}
