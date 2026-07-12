package cl.eos.dipalza.controller;

import cl.eos.dipalza.model.RutaDTO;
import cl.eos.dipalza.service.RutaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = RutaController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
class RutaControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean RutaService service;

    private RutaDTO dto(String codigo) {
        RutaDTO d = new RutaDTO();
        d.setCodigo(codigo);
        d.setDescripcion("Ruta Norte");
        d.setCodigoConduccion("C1");
        return d;
    }

    @Test
    void getAllRutas_retornaLista() throws Exception {
        when(service.getAllRutas()).thenReturn(List.of(dto("R01")));
        mockMvc.perform(get("/api/rutas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].codigo", is("R01")));
    }

    @Test
    void getRutaById_existente_retorna200() throws Exception {
        when(service.getRutaById("R01")).thenReturn(Optional.of(dto("R01")));
        mockMvc.perform(get("/api/rutas/R01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codigo", is("R01")));
    }

    @Test
    void getRutaById_noExiste_retorna404() throws Exception {
        when(service.getRutaById("XX")).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/rutas/XX"))
                .andExpect(status().isNotFound());
    }
}
