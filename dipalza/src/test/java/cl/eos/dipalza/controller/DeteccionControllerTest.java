package cl.eos.dipalza.controller;

import cl.eos.dipalza.model.ParadaVendedorDTO;
import cl.eos.dipalza.service.DeteccionService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = DeteccionController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
class DeteccionControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private DeteccionService deteccionService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void obtenerHistorico_retorna200ConLasParadas() throws Exception {
        ParadaVendedorDTO dto = new ParadaVendedorDTO(1L, "001", "V", "Juan Perez",
                -33.45, -70.65, "Av. Providencia",
                LocalDateTime.of(2026, 8, 1, 10, 0), LocalDateTime.of(2026, 8, 1, 10, 15), false);
        when(deteccionService.buscarHistorico(any(PosicionFilter.class))).thenReturn(List.of(dto));

        mockMvc.perform(post("/api/deteccion/historico")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PosicionFilter(null, null, null, LocalDate.of(2026, 8, 1)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].calle").value("Av. Providencia"));
    }
}
