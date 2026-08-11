package cl.eos.dipalza.controller;

import cl.eos.dipalza.model.NumeradoDTO;
import cl.eos.dipalza.model.NumeradoResumenDTO;
import cl.eos.dipalza.model.ProductoElegibleNumeradoDTO;
import cl.eos.dipalza.service.NumeradosService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = NumeradosController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
class NumeradosControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean NumeradosService service;

    private NumeradoDTO dto(Long id) {
        NumeradoDTO d = new NumeradoDTO();
        d.setId(id);
        d.setCodigoProducto("ART001");
        d.setNumero(1);
        d.setPeso(BigDecimal.valueOf(10));
        d.setEstado("D");
        return d;
    }

    @Test
    void getAllNumerados_retornaLista() throws Exception {
        when(service.findAll()).thenReturn(List.of(dto(1L)));
        mockMvc.perform(get("/api/numerados"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].codigoProducto", is("ART001")));
    }

    @Test
    void getNumeradosByCodigoProducto_retornaFiltrados() throws Exception {
        when(service.findByProducto("ART001")).thenReturn(List.of(dto(1L)));
        mockMvc.perform(get("/api/numerados/byProduct").param("codigoProducto", "ART001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void getGroupedNumerados_retornaResumen() throws Exception {
        NumeradoResumenDTO resumen = new NumeradoResumenDTO("ART001", "Queso", BigDecimal.valueOf(12.5), 5L);
        when(service.findGrouped()).thenReturn(List.of(resumen));
        mockMvc.perform(get("/api/numerados/resumen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void getNumeradosByEstado_retornaFiltrados() throws Exception {
        when(service.findAllByEstado("D")).thenReturn(List.of(dto(1L)));
        mockMvc.perform(get("/api/numerados/estados").param("estado", "D"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void createNumerado_retornaDTO() throws Exception {
        NumeradoDTO d = dto(null);
        when(service.save(any())).thenReturn(dto(1L));
        mockMvc.perform(post("/api/numerados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(d)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(1)));
    }

    @Test
    void findPesoPromedioArticulo_retornaFloat() throws Exception {
        when(service.findPrecioPromedioArticulo("ART001")).thenReturn(15.5f);
        mockMvc.perform(get("/api/numerados/pesopromedio/ART001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", is(15.5)));
    }

    @Test
    void createNumerado_productoNoNumerado_retorna400() throws Exception {
        NumeradoDTO d = dto(null);
        when(service.save(any())).thenThrow(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "El producto no está marcado como numerado"));

        mockMvc.perform(post("/api/numerados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(d)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createNumerado_numeroDuplicado_retorna400() throws Exception {
        NumeradoDTO d = dto(null);
        when(service.save(any())).thenThrow(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ya existe un numerado activo con ese número para este producto"));

        mockMvc.perform(post("/api/numerados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(d)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getProductosElegibles_retornaLista() throws Exception {
        ProductoElegibleNumeradoDTO dto = new ProductoElegibleNumeradoDTO(
                "ART001", "Queso", BigDecimal.valueOf(50), BigDecimal.valueOf(3), false);
        when(service.findProductosElegibles()).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/numerados/productos-elegibles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].codigoProducto", is("ART001")))
                .andExpect(jsonPath("$[0].tieneRegistrosAsociados", is(false)));
    }

    @Test
    void marcarProductoElegible_ok_retorna200() throws Exception {
        mockMvc.perform(put("/api/numerados/productos-elegibles/ART001"))
                .andExpect(status().isOk());

        verify(service).marcarProductoComoNumerado("ART001");
    }

    @Test
    void marcarProductoElegible_productoNoExiste_retorna404() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Producto no encontrado"))
                .when(service).marcarProductoComoNumerado("NOEXISTE");

        mockMvc.perform(put("/api/numerados/productos-elegibles/NOEXISTE"))
                .andExpect(status().isNotFound());
    }

    @Test
    void desmarcarProductoElegible_ok_retorna200() throws Exception {
        mockMvc.perform(delete("/api/numerados/productos-elegibles/ART001"))
                .andExpect(status().isOk());

        verify(service).desmarcarProductoComoNumerado("ART001");
    }

    @Test
    void desmarcarProductoElegible_conRegistrosAsociados_retorna400() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "No se puede quitar: el producto tiene numerados asociados"))
                .when(service).desmarcarProductoComoNumerado("ART001");

        mockMvc.perform(delete("/api/numerados/productos-elegibles/ART001"))
                .andExpect(status().isBadRequest());
    }
}
