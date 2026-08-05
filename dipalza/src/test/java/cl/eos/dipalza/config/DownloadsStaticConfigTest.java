package cl.eos.dipalza.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Desviación respecto al brief: @WebMvcTest sin controladores explícitos
// escanea TODOS los @Controller/@RestController de la app (no solo los del
// slice), lo que arrastra a AuthController y su dependencia de UserRepo
// (un bean de persistencia no disponible en este test de slice web). Se
// acota el slice a un controlador vacío dedicado para que el contexto solo
// cargue DownloadsStaticConfig y su ResourceHandler, preservando el
// comportamiento objetivo del brief: GET a un archivo existente -> 200 con
// su contenido, GET a uno inexistente -> 404, sin pasar por filtros reales.
@WebMvcTest(controllers = DownloadsStaticConfigTest.NoOpController.class)
@Import(DownloadsStaticConfig.class)
@AutoConfigureMockMvc(addFilters = false)
class DownloadsStaticConfigTest {

    @RestController
    static class NoOpController {
    }

    @TempDir
    static Path tempDownloadsDir;

    @DynamicPropertySource
    static void configurarRutaDeDescargas(DynamicPropertyRegistry registry) {
        registry.add("app.downloads.location", () -> "file:" + tempDownloadsDir + "/");
    }

    @Autowired
    MockMvc mockMvc;

    @BeforeEach
    void crearArchivoDePrueba() throws IOException {
        Files.writeString(tempDownloadsDir.resolve("dipalza.apk"), "contenido-apk-de-prueba");
    }

    @Test
    void sirveElArchivoDesdeElDirectorioConfigurado() throws Exception {
        mockMvc.perform(get("/downloads/dipalza.apk"))
                .andExpect(status().isOk())
                .andExpect(content().string("contenido-apk-de-prueba"));
    }

    @Test
    void respondeNotFoundParaUnArchivoQueNoExiste() throws Exception {
        mockMvc.perform(get("/downloads/inexistente.apk"))
                .andExpect(status().isNotFound());
    }
}
