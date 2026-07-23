package cl.eos.dipalza.controller;

import cl.eos.dipalza.model.VendedorDTO;
import cl.eos.dipalza.service.VendedorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = VendedorController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
class VendedorControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean VendedorService service;

    @Test
    void listarVendedores_retorna200ConListaCompleta() throws Exception {
        VendedorDTO dto = new VendedorDTO("001", "0", "11.111.111-1", "Juan Perez", "Santiago", "Providencia", "Calle 1", "912345678");
        when(service.listarTodos()).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/vendedores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].codigo", is("001")))
                .andExpect(jsonPath("$[0].nombre", is("Juan Perez")));
    }
}
