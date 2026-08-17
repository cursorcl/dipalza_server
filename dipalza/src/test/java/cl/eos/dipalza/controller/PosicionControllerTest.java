package cl.eos.dipalza.controller;

import cl.eos.dipalza.model.HistorialPosicionDTO;
import cl.eos.dipalza.model.HistorialResumenDiaDTO;
import cl.eos.dipalza.model.PosicionDTO;
import cl.eos.dipalza.service.PosicionService;
import cl.eos.dipalza.specifications.PosicionFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = PosicionController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
class PosicionControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean PosicionService service;
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void obtenerPosiciones_retorna200ConLista() throws Exception {
        PosicionDTO dto = new PosicionDTO("V01", "0 ", "Juan", LocalDateTime.now(), -33.4, -70.6);
        when(service.obtenerActuales()).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/posicion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].vendedorId", is("V01")));
    }

    @Test
    void obtenerHistorico_conFiltro_retorna200() throws Exception {
        HistorialPosicionDTO h = new HistorialPosicionDTO(1L, "V01", "0 ", "Juan", LocalDateTime.now(), -33.4, -70.6);
        when(service.buscarHistorico(any())).thenReturn(List.of(h));

        PosicionFilter filter = new PosicionFilter(null, null, null, null);
        mockMvc.perform(post("/api/posicion/historico")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(filter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void obtenerResumenHistorico_retorna200ConLista() throws Exception {
        HistorialResumenDiaDTO dto = new HistorialResumenDiaDTO(
                LocalDate.of(2026, 8, 10), 120L,
                LocalDateTime.of(2026, 8, 10, 10, 0), LocalDateTime.of(2026, 8, 10, 19, 0));
        when(service.buscarResumenHistorico("V01", "0 ")).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/posicion/historico/resumen")
                        .param("vendedorCodigo", "V01")
                        .param("vendedorTipo", "0 "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].cantidadPuntos", is(120)));
    }

    @Test
    void registrarPosicion_retorna202() throws Exception {
        PosicionDTO dto = new PosicionDTO("V01", "0 ", "Juan", LocalDateTime.now(), -33.4, -70.6);
        doNothing().when(service).registrarUbicacion(any());

        mockMvc.perform(post("/api/posicion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isAccepted());
    }
}
