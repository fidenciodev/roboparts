package br.com.roboparts.security;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository contextRepository,
            HttpSessionCsrfTokenRepository csrfTokens, AuthRateLimiter limiter) throws Exception {
        return http.cors(cors -> { })
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens)
                        .csrfTokenRequestHandler(new XorCsrfTokenRequestAttributeHandler()))
                .securityContext(context -> context.requireExplicitSave(true).securityContextRepository(contextRepository))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf", "/actuator/health", "/swagger-ui.html",
                                "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/me", "/api/auth/activities", "/api/system/status").hasRole("EMPLOYEE")
                        .requestMatchers(HttpMethod.POST, "/api/auth/logout").hasRole("EMPLOYEE")
                        .requestMatchers(HttpMethod.GET, "/api/robots", "/api/robots/**", "/api/checklists", "/api/checklists/**",
                                "/api/dashboard", "/api/history").hasRole("EMPLOYEE")
                        .requestMatchers(HttpMethod.POST, "/api/robots", "/api/robots/**", "/api/checklists", "/api/checklists/**").hasRole("EMPLOYEE")
                        .anyRequest().denyAll())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .addFilterBefore(new AuthRateLimitFilter(limiter), CsrfFilter.class)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> SecurityErrorResponse.write(response,
                                401, "unauthenticated", "Acesso não autorizado", "Entre na sua conta para continuar."))
                        .accessDeniedHandler((request, response, exception) -> {
                            if (exception instanceof CsrfException) {
                                SecurityErrorResponse.write(response, 403, "csrf_invalid", "Sessão de segurança inválida",
                                        "Atualize a sessão de segurança e tente novamente.");
                            } else {
                                SecurityErrorResponse.write(response, 403, "access_denied", "Acesso negado",
                                        "Você não tem acesso a esta operação.");
                            }
                        }))
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        Pbkdf2PasswordEncoder encoder = new Pbkdf2PasswordEncoder("", 16, 600000, 256);
        encoder.setAlgorithm(Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
        return encoder;
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder passwords) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwords);
        // DaoAuthenticationProvider performs a dummy hash verification for an unknown email.
        return new ProviderManager(provider);
    }

    @Bean
    SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }

    @Bean
    HttpSessionCsrfTokenRepository csrfTokenRepository() { return new HttpSessionCsrfTokenRepository(); }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(HttpSessionCsrfTokenRepository repository) {
        CsrfAuthenticationStrategy csrf = new CsrfAuthenticationStrategy(repository);
        csrf.setRequestHandler(new XorCsrfTokenRequestAttributeHandler());
        return new CompositeSessionAuthenticationStrategy(List.of(new ChangeSessionIdAuthenticationStrategy(), csrf));
    }

    @Bean
    AuthRateLimiter authRateLimiter(@Value("${app.auth.rate-limit.requests:20}") int requests,
            @Value("${app.auth.rate-limit.window-seconds:60}") int windowSeconds,
            @Value("${app.auth.rate-limit.capacity:4096}") int capacity) {
        return new AuthRateLimiter(requests, Duration.ofSeconds(windowSeconds), capacity);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins}") String origins) {
        List<String> allowed = Arrays.stream(origins.split(",")).map(String::trim)
                .filter(origin -> !origin.isEmpty()).toList();
        if (allowed.isEmpty() || allowed.stream().anyMatch(origin -> origin.contains("*"))) {
            throw new IllegalArgumentException("Configure origens CORS explícitas, sem curingas.");
        }
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowed);
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Accept", "Content-Type", "X-CSRF-TOKEN"));
        config.setExposedHeaders(List.of("Retry-After"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
