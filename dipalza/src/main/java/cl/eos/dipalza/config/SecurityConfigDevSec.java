package cl.eos.dipalza.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import cl.eos.dipalza.filter.JwtAuthFilter;

@Configuration
@EnableMethodSecurity
@Profile("dev-sec")
public class SecurityConfigDevSec {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // Si JwtAuthFilter tiene dependencias, declara aquí su @Bean con @Autowired
    @Bean
    JwtAuthFilter jwtFilter() {
        return new JwtAuthFilter();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
        .csrf(AbstractHttpConfigurer::disable) // REST
        .cors(cors -> cors.configure(http))
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/index.html").permitAll()
                .requestMatchers("/*.js", "/*.css", "/*.ico", "/*.json").permitAll()
                .requestMatchers("/*.woff", "/*.woff2", "/*.ttf", "/*.eot", "/*.otf").permitAll()
                .requestMatchers("/assets/**", "/media/**", "/chunk-**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/rutas", "/ping").permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                .requestMatchers("/ws-posiciones/**").permitAll()
                .requestMatchers("/auth/**").permitAll()
                .anyRequest().authenticated()
        )
        .addFilterBefore(jwtFilter(), UsernamePasswordAuthenticationFilter.class);

    return http.build();
    }
}
