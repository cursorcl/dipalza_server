package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.AppRole;
import cl.eos.dipalza.entity.AppUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class JwtServiceTest {

    private JwtService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new JwtService();
        setField("secret", "8f1d7c0a9b52e34f67a8d5c2b19e04fa37b1e2c4f85d09ab23cd4567e90fab12");
        setField("issuer", "dipalza-test");
        setField("accessMin", 10L);
        setField("refreshHr", 5L);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = JwtService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    private AppUser usuario(String username) {
        AppRole role = new AppRole();
        role.setName("ROLE_VENDEDOR");
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setPassword("hash");
        u.setRoles(Set.of(role));
        return u;
    }

    @Test
    void generateAccess_retornaTokenNoVacio() {
        String token = service.generateAccess(usuario("jdoe"));
        assertThat(token).isNotBlank();
    }

    @Test
    void generateAccess_tokenContieneClaim_roles() {
        String token = service.generateAccess(usuario("jdoe"));
        Claims claims = service.parse(token).getPayload();
        assertThat(claims.get("roles")).isNotNull();
        assertThat(claims.get("type", String.class)).isEqualTo("ACCESS");
    }

    @Test
    void generateRefresh_tokenNoContieneRoles() {
        String token = service.generateRefresh(usuario("jdoe"));
        Claims claims = service.parse(token).getPayload();
        assertThat(claims.get("roles")).isNull();
        assertThat(claims.get("type", String.class)).isEqualTo("REFRESH");
    }

    @Test
    void extractUsername_retornaElSubject() {
        String token = service.generateAccess(usuario("ana@test.cl"));
        assertThat(service.extractUsername(token)).isEqualTo("ana@test.cl");
    }

    @Test
    void parse_tokenValido_retornaJwsConClaims() {
        String token = service.generateAccess(usuario("jdoe"));
        Jws<Claims> jws = service.parse(token);
        assertThat(jws.getPayload().getSubject()).isEqualTo("jdoe");
        assertThat(jws.getPayload().getIssuer()).isEqualTo("dipalza-test");
    }

    @Test
    void parse_tokenInvalido_lanzaJwtException() {
        assertThatThrownBy(() -> service.parse("not.a.valid.jwt"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void parse_tokenFirmadoConOtroSecret_lanzaJwtException() throws Exception {
        JwtService otro = new JwtService();
        Field f = JwtService.class.getDeclaredField("secret");
        f.setAccessible(true);
        f.set(otro, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Field fi = JwtService.class.getDeclaredField("issuer");
        fi.setAccessible(true);
        fi.set(otro, "x");
        Field fa = JwtService.class.getDeclaredField("accessMin");
        fa.setAccessible(true);
        fa.set(otro, 10L);
        Field fr = JwtService.class.getDeclaredField("refreshHr");
        fr.setAccessible(true);
        fr.set(otro, 5L);

        String tokenAjeno = otro.generateAccess(usuario("hacker"));
        assertThatThrownBy(() -> service.parse(tokenAjeno))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void generateAccess_yExtraUsername_roundtrip() {
        AppUser u = usuario("vendedor@test.cl");
        String token = service.generateAccess(u);
        assertThat(service.extractUsername(token)).isEqualTo("vendedor@test.cl");
    }
}
