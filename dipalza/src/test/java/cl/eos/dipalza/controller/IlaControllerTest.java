package cl.eos.dipalza.controller;

import cl.eos.dipalza.model.IlaDTO;
import cl.eos.dipalza.service.IlaService;
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

@WebMvcTest(value = IlaController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
class IlaControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean IlaService service;

    private IlaDTO dto(String codigo) {
        IlaDTO d = new IlaDTO();
        d.setCodigo(codigo);
        d.setDescripcion("Bebidas");
        d.setValor(BigDecimal.valueOf(27));
        return d;
    }

    @Test
    void getAllIla_retornaLista() throws Exception {
        when(service.findAllByOrderByDescripcionAsc()).thenReturn(List.of(dto("I1")));
        mockMvc.perform(get("/api/ila"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].codigo", is("I1")));
    }

    @Test
    void getIlaById_existente_retorna200() throws Exception {
        when(service.getIlaById("I1")).thenReturn(Optional.of(dto("I1")));
        mockMvc.perform(get("/api/ila/I1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.descripcion", is("Bebidas")));
    }

    @Test
    void getIlaById_noExiste_retorna404() throws Exception {
        when(service.getIlaById("XX")).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/ila/XX"))
                .andExpect(status().isNotFound());
    }
}
