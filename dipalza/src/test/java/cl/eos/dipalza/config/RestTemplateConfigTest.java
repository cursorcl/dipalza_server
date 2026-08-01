package cl.eos.dipalza.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// classes= aquí evita que @SpringBootTest arranque toda la aplicación (BD, seguridad, etc.);
// RestTemplateAutoConfiguration se agrega explícitamente porque es la que normalmente provee
// el bean RestTemplateBuilder que RestTemplateConfig.restTemplate(...) requiere, y al fijar
// `classes` se pierde la auto-configuración implícita de @SpringBootApplication.
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {RestTemplateConfig.class, RestTemplateAutoConfiguration.class})
class RestTemplateConfigTest {

    @Autowired RestTemplate restTemplate;

    @Test
    void restTemplate_enviaUserAgentDescriptivo_yExpandeLatLonEnOrdenCorrecto() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("https://nominatim.openstreetmap.org/reverse?lat=-33.04&lon=-71.62&format=jsonv2"))
                .andExpect(header("User-Agent", containsString("DipalzaVentas")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        restTemplate.getForObject(
                "https://nominatim.openstreetmap.org/reverse?lat={lat}&lon={lon}&format=jsonv2",
                String.class, -33.04, -71.62);

        server.verify();
    }
}
