package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.Configuracion;
import cl.eos.dipalza.repository.ConfiguracionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfiguracionServiceTest {

    @Mock ConfiguracionRepository repo;
    ConfiguracionService service;

    private Configuracion config(String clave, String valor) {
        Configuracion c = new Configuracion();
        c.setPropiedad(clave);
        c.setValor(valor);
        return c;
    }

    @BeforeEach
    void setUp() {
        service = new ConfiguracionService(repo);
        when(repo.findAll()).thenReturn(List.of(
                config("clave.texto", "hola"),
                config("clave.entero", "42"),
                config("clave.decimal", "3.14"),
                config("clave.bool", "true")
        ));
        service.cargarCache();
    }

    @Test
    void getString_claveExistente_retornaValor() {
        assertThat(service.getString("clave.texto")).isEqualTo("hola");
    }

    @Test
    void getString_claveInexistente_retornaVacio() {
        assertThat(service.getString("no.existe")).isEqualTo("");
    }

    @Test
    void getInt_claveExistente_retornaEntero() {
        assertThat(service.getInt("clave.entero")).isEqualTo(42);
    }

    @Test
    void getInt_valorNoNumerico_retornaCero() {
        when(repo.findAll()).thenReturn(List.of(config("clave.rota", "abc")));
        service.cargarCache();
        assertThat(service.getInt("clave.rota")).isEqualTo(0);
    }

    @Test
    void getDouble_claveExistente_retornaDecimal() {
        assertThat(service.getDouble("clave.decimal")).isEqualTo(3.14);
    }

    @Test
    void getBoolean_claveTrue_retornaTrue() {
        assertThat(service.getBoolean("clave.bool")).isTrue();
    }

    @Test
    void getBoolean_claveInexistente_retornaFalse() {
        assertThat(service.getBoolean("no.existe")).isFalse();
    }

    @Test
    void actualizarConfig_claveExistente_actualizaCacheYRepo() {
        Configuracion existing = config("clave.texto", "hola");
        when(repo.findById("clave.texto")).thenReturn(java.util.Optional.of(existing));
        when(repo.save(existing)).thenReturn(existing);

        service.actualizarConfig("clave.texto", "mundo");
        assertThat(service.getString("clave.texto")).isEqualTo("mundo");
        verify(repo).save(existing);
    }

    @Test
    void actualizarConfig_claveInexistente_lanzaRuntimeException() {
        when(repo.findById("no.existe")).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service.actualizarConfig("no.existe", "x"))
                .isInstanceOf(RuntimeException.class);
    }
}
