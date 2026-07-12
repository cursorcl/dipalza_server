package cl.eos.dipalza.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = PingController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
class PingControllerTest {

    @Autowired MockMvc mockMvc;

    @Test
    void health_retorna200() throws Exception {
        mockMvc.perform(get("/ping"))
                .andExpect(status().isOk());
    }

    @Test
    void health_retornaStatusUP() throws Exception {
        mockMvc.perform(get("/ping"))
                .andExpect(jsonPath("$.status", is("UP")))
                .andExpect(jsonPath("$.service", is("VentasAPI")));
    }
}
