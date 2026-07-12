package cl.eos.dipalza.controller;

import cl.eos.dipalza.model.CondicionVentaDTO;
import cl.eos.dipalza.service.CondicionVentaService;
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

@WebMvcTest(value = CondicionVentaController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
class CondicionVentaControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean CondicionVentaService service;

    private CondicionVentaDTO dto(String codigo) {
        CondicionVentaDTO d = new CondicionVentaDTO();
        d.setCodigo(codigo);
        d.setDescripcion("Contado");
        d.setDias(0);
        return d;
    }

    @Test
    void getAllCondicionVenta_retornaLista() throws Exception {
        when(service.getAllCondicionVentas()).thenReturn(List.of(dto("01")));
        mockMvc.perform(get("/api/condicionventa"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].codigo", is("01")));
    }

    @Test
    void getCondicionVentaById_existente_retorna200() throws Exception {
        when(service.getCondicionVentaById("01")).thenReturn(Optional.of(dto("01")));
        mockMvc.perform(get("/api/condicionventa/01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.descripcion", is("Contado")));
    }

    @Test
    void getCondicionVentaById_noExiste_retorna404() throws Exception {
        when(service.getCondicionVentaById("99")).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/condicionventa/99"))
                .andExpect(status().isNotFound());
    }
}
