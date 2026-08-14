package cl.eos.dipalza.filter;

import cl.eos.dipalza.repository.UserRepo;
import cl.eos.dipalza.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class JwtAuthFilter extends OncePerRequestFilter {

    // Único endpoint de /api/** que sigue disponible mientras el usuario tiene
    // el cambio de clave pendiente: es el que lo saca de ese estado.
    private static final String RUTA_CAMBIAR_CLAVE = "/api/usuario/cambiar-clave";
    private static final String MENSAJE_CAMBIO_PENDIENTE = "Debe cambiar su clave antes de continuar";

    @Autowired
    JwtService jwt;
    @Autowired
    UserRepo users;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest req, @NonNull HttpServletResponse res, @NonNull FilterChain fc) throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        if(h != null && h.startsWith("Bearer ")) {
            try {
                var jws = jwt.parse(h.substring(7));
                String username = jws.getPayload().getSubject();
                var user = users.findByUsername(username).orElseThrow();
                // Enforcement server-side: aunque el cliente (móvil o web) tenga un
                // bug que le permita saltarse la pantalla de cambio obligatorio, el
                // servidor no deja usar la API hasta que la clave se cambie.
                if (user.isMustChangePassword() && bloqueaPorCambioClavePendiente(req)) {
                    SecurityContextHolder.clearContext();
                    res.sendError(HttpServletResponse.SC_FORBIDDEN, MENSAJE_CAMBIO_PENDIENTE);
                    return;
                }
                var authorities = user.getRoles().stream().map(r -> new SimpleGrantedAuthority(r.getName())).toList();
                var auth = new UsernamePasswordAuthenticationToken(username, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch(RuntimeException ignored) {
                // Si el token falla, limpiamos el contexto para asegurar que sea tratado como anónimo
                SecurityContextHolder.clearContext();
            }
        }
        fc.doFilter(req, res);
    }

    // Solo se bloquea /api/**; /auth/** (login, refresh, forgot-password) y los
    // recursos estáticos siguen pasando, porque son los que permiten al usuario
    // autenticarse y llegar al cambio de clave.
    private boolean bloqueaPorCambioClavePendiente(HttpServletRequest req) {
        String path = req.getServletPath();
        if (path == null || path.isEmpty())
            path = req.getRequestURI();
        if (path == null || !path.startsWith("/api/"))
            return false;
        return !path.equals(RUTA_CAMBIAR_CLAVE);
    }
}

