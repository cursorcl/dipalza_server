package cl.eos.dipalza.controller;

import cl.eos.dipalza.service.GeocodificacionService;
import jakarta.servlet.ServletException;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = GeocodificacionController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
class GeocodificacionControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean GeocodificacionService service;

    @Test
    void obtenerCalle_retorna200ConLaCalleDelService() throws Exception {
        when(service.obtenerCalle(anyDouble(), anyDouble())).thenReturn("Av. Errázuriz");

        mockMvc.perform(get("/api/geocodificacion/inversa").param("lat", "-33.0393").param("lon", "-71.6273"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calle", is("Av. Errázuriz")));
    }

    @Test
    void obtenerCalle_redondeaLatYLonA5DecimalesAntesDeLlamarAlService() throws Exception {
        when(service.obtenerCalle(anyDouble(), anyDouble())).thenReturn("Calle X");

        mockMvc.perform(get("/api/geocodificacion/inversa").param("lat", "-33.039312345").param("lon", "-71.627298765"))
                .andExpect(status().isOk());

        verify(service).obtenerCalle(-33.03931, -71.6273);
    }

    // Nota: en el contexto mínimo de @WebMvcTest no existe un @ExceptionHandler para
    // ConstraintViolationException, por lo que MockMvc no produce una respuesta con código de
    // estado: perform() propaga una ServletException que envuelve la ConstraintViolationException.
    // En la aplicación real (contexto completo de Spring Boot), este mismo caso se traduce a un
    // 500 por el mismo motivo (no hay un @ControllerAdvice que lo mapee a 400) — comportamiento
    // preexistente, fuera del alcance de esta corrección; lo que este test prueba es la
    // invariante importante: la solicitud inválida nunca llega al service.
    @Test
    void obtenerCalle_latFueraDeRango_rechazaLaSolicitudYNuncaLlamaAlService() {
        ServletException ex = assertThrows(ServletException.class, () ->
                mockMvc.perform(get("/api/geocodificacion/inversa").param("lat", "500").param("lon", "-71.6273")));

        assertThat(ex.getCause()).isInstanceOf(ConstraintViolationException.class);
        verify(service, never()).obtenerCalle(anyDouble(), anyDouble());
    }

    @Test
    void obtenerCalle_lonFueraDeRango_rechazaLaSolicitudYNuncaLlamaAlService() {
        ServletException ex = assertThrows(ServletException.class, () ->
                mockMvc.perform(get("/api/geocodificacion/inversa").param("lat", "-33.0393").param("lon", "-500")));

        assertThat(ex.getCause()).isInstanceOf(ConstraintViolationException.class);
        verify(service, never()).obtenerCalle(anyDouble(), anyDouble());
    }
}
