package cl.eos.dipalza.controller;

import cl.eos.dipalza.model.ClienteDTO;
import cl.eos.dipalza.service.ClienteService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = ClienteController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
class ClienteControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean ClienteService service;

    private ClienteDTO dto(String rut, String codigo) {
        ClienteDTO d = new ClienteDTO();
        d.setRut(rut);
        d.setCodigo(codigo);
        d.setRazon("Empresa Test SA");
        d.setCodigoRuta("R01");
        return d;
    }

    @Test
    void getAllClientes_retornaLista() throws Exception {
        when(service.getAllClientes()).thenReturn(List.of(dto("11111111-1", "001")));
        mockMvc.perform(get("/api/clientes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rut", is("11111111-1")));
    }

    @Test
    void getClientesByRuta_retornaFiltrados() throws Exception {
        when(service.getClientesByRuta("R01")).thenReturn(List.of(dto("11111111-1", "001")));
        mockMvc.perform(get("/api/clientes/ruta/R01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void getClienteById_existente_retorna200() throws Exception {
        when(service.getClienteById("11111111-1", "001")).thenReturn(Optional.of(dto("11111111-1", "001")));
        mockMvc.perform(get("/api/clientes/11111111-1").param("codigo", "001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.razon", is("Empresa Test SA")));
    }

    @Test
    void getClienteById_noExiste_retorna404() throws Exception {
        when(service.getClienteById("99999999-9", "   ")).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/clientes/99999999-9"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getClienteByVendedor_retornaLista() throws Exception {
        when(service.getClientesByVendedor("V01")).thenReturn(List.of(dto("11111111-1", "001")));
        mockMvc.perform(get("/api/clientes/vendedor").param("codigoVendedor", "V01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void createCliente_retornaDTO() throws Exception {
        ClienteDTO d = dto("11111111-1", "001");
        when(service.createOrUpdateCliente(any())).thenReturn(d);
        mockMvc.perform(post("/api/clientes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(d)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rut", is("11111111-1")));
    }

    @Test
    void updateCliente_existente_retorna200() throws Exception {
        ClienteDTO d = dto("11111111-1", "001");
        when(service.getClienteById("11111111-1", "001")).thenReturn(Optional.of(d));
        when(service.createOrUpdateCliente(any())).thenReturn(d);
        mockMvc.perform(put("/api/clientes/11111111-1/001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(d)))
                .andExpect(status().isOk());
    }

    @Test
    void deleteCliente_existente_retorna204() throws Exception {
        when(service.deleteCliente("11111111-1", "001")).thenReturn(true);
        mockMvc.perform(delete("/api/clientes/11111111-1/001"))
                .andExpect(status().isNoContent());
    }
}
