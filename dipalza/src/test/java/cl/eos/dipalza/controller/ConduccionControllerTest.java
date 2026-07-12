package cl.eos.dipalza.controller;

import cl.eos.dipalza.model.ConduccionDTO;
import cl.eos.dipalza.service.ConduccionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = ConduccionController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
class ConduccionControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean ConduccionService service;

    private ConduccionDTO dto(String codigo) {
        ConduccionDTO d = new ConduccionDTO();
        d.setCodigo(codigo);
        d.setDescripcion("Camión");
        d.setValor(BigDecimal.valueOf(1500));
        return d;
    }

    @Test
    void getAllConduccion_retornaLista() throws Exception {
        when(service.getAllConduccions()).thenReturn(List.of(dto("C1")));
        mockMvc.perform(get("/api/conduccion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].codigo", is("C1")));
    }

    @Test
    void getConduccionById_existente_retorna200() throws Exception {
        when(service.getConduccionById("C1")).thenReturn(Optional.of(dto("C1")));
        mockMvc.perform(get("/api/conduccion/C1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.descripcion", is("Camión")));
    }

    @Test
    void getConduccionById_noExiste_retorna404() throws Exception {
        when(service.getConduccionById("XX")).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/conduccion/XX"))
                .andExpect(status().isNotFound());
    }
}
