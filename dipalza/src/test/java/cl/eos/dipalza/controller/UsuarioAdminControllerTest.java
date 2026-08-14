package cl.eos.dipalza.controller;

import cl.eos.dipalza.model.ActualizarUsuarioDTO;
import cl.eos.dipalza.model.CrearUsuarioDTO;
import cl.eos.dipalza.model.CrearUsuarioResultDTO;
import cl.eos.dipalza.model.UsuarioDTO;
import cl.eos.dipalza.service.UsuarioAdminService;
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

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = UsuarioAdminController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
class UsuarioAdminControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean UsuarioAdminService service;

    private UsuarioDTO dto(Long id) {
        return new UsuarioDTO(id, "jperez", "j@dipalza.cl", null, null, null, true, false, null);
    }

    @Test
    void listar_retornaLista() throws Exception {
        when(service.listar()).thenReturn(List.of(dto(1L)));
        mockMvc.perform(get("/api/usuarios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].username", is("jperez")));
    }

    @Test
    void obtener_existente_retornaDto() throws Exception {
        when(service.obtener(1L)).thenReturn(dto(1L));
        mockMvc.perform(get("/api/usuarios/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(1)));
    }

    @Test
    void obtener_noExistente_retorna404() throws Exception {
        when(service.obtener(99L)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado"));
        mockMvc.perform(get("/api/usuarios/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void crear_valido_retornaResultado() throws Exception {
        CrearUsuarioDTO req = new CrearUsuarioDTO("nuevo", "n@dipalza.cl", null, null, "claveLarga1");
        when(service.crear(any())).thenReturn(new CrearUsuarioResultDTO(dto(1L), true));

        mockMvc.perform(post("/api/usuarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario.id", is(1)))
                .andExpect(jsonPath("$.correoEnviado", is(true)));
    }

    @Test
    void crear_usernameDuplicado_retorna400() throws Exception {
        when(service.crear(any())).thenThrow(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ya existe un usuario con ese username"));

        mockMvc.perform(post("/api/usuarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CrearUsuarioDTO("jperez", null, null, null, "claveLarga1"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void actualizar_valido_retornaDto() throws Exception {
        ActualizarUsuarioDTO req = new ActualizarUsuarioDTO("j@dipalza.cl", null, null, true, false);
        when(service.actualizar(eq(1L), any())).thenReturn(dto(1L));

        mockMvc.perform(put("/api/usuarios/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username", is("jperez")));
    }

    @Test
    void actualizar_noExistente_retorna404() throws Exception {
        when(service.actualizar(eq(99L), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado"));

        mockMvc.perform(put("/api/usuarios/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ActualizarUsuarioDTO(null, null, null, true, false))))
                .andExpect(status().isNotFound());
    }

    @Test
    void habilitar_retornaDto() throws Exception {
        when(service.habilitar(1L)).thenReturn(dto(1L));
        mockMvc.perform(patch("/api/usuarios/1/habilitar"))
                .andExpect(status().isOk());
        verify(service).habilitar(1L);
    }

    @Test
    void deshabilitar_retornaDto() throws Exception {
        when(service.deshabilitar(1L)).thenReturn(dto(1L));
        mockMvc.perform(patch("/api/usuarios/1/deshabilitar"))
                .andExpect(status().isOk());
        verify(service).deshabilitar(1L);
    }

    @Test
    void bloquear_retornaDto() throws Exception {
        when(service.bloquear(1L)).thenReturn(dto(1L));
        mockMvc.perform(patch("/api/usuarios/1/bloquear"))
                .andExpect(status().isOk());
        verify(service).bloquear(1L);
    }

    @Test
    void desbloquear_retornaDto() throws Exception {
        when(service.desbloquear(1L)).thenReturn(dto(1L));
        mockMvc.perform(patch("/api/usuarios/1/desbloquear"))
                .andExpect(status().isOk());
        verify(service).desbloquear(1L);
    }
}
