package com.teste.vinculos.web.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.Set;

/**
 * Cadeia de segurança: API sem estado no servidor (JWT), com dois jeitos de enviar o token:
 * {@code Authorization: Bearer} (Postman, integrações) ou o cookie HttpOnly da sessão do navegador.
 *
 * <p>Cada grupo de endpoints exige o seu escopo. Erros de autenticação (401) e de permissão (403) passam pelo
 * {@code ApiExceptionHandler}, no mesmo formato RFC 9457 dos demais erros.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@Profile("!seed")
public class SecurityConfig {

    /** Endpoints de login/sessão que recebem POST sem token (o token ainda não existe ou já expirou). */
    static final Set<String> PUBLIC_AUTH_POSTS = Set.of(
            "/api/v1/auth/token", "/api/v1/auth/session", "/api/v1/auth/refresh", "/api/v1/auth/logout");
    static final String JWKS = "/api/v1/auth/jwks";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, BearerTokenResolver tokenResolver,
                                            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors)
            throws Exception {
        return http
                // CSRF: o token de sessão só vai em cookie SameSite=Strict, que o navegador não manda a partir de
                // outro site; o Bearer no header nunca é enviado sozinho pelo navegador.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.POST, PUBLIC_AUTH_POSTS.toArray(String[]::new)).permitAll()
                        .requestMatchers(HttpMethod.GET, JWKS).permitAll()
                        .requestMatchers(
                                "/actuator/health", "/actuator/health/**",
                                "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
                                "/error")
                        .permitAll()
                        .requestMatchers("/api/v1/customers/export").hasAuthority(Scopes.authority(Scopes.CUSTOMERS_EXPORT))
                        .requestMatchers("/api/v1/customers/**").hasAuthority(Scopes.authority(Scopes.CUSTOMERS_READ))
                        .requestMatchers("/api/v1/audit/**").hasAuthority(Scopes.authority(Scopes.AUDIT_READ))
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(tokenResolver)
                        .authenticationEntryPoint((request, response, e) -> errors.resolveException(request, response, null, e))
                        .accessDeniedHandler((request, response, e) -> errors.resolveException(request, response, null, e))
                        .jwt(jwt -> { }))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, e) -> errors.resolveException(request, response, null, e))
                        .accessDeniedHandler((request, response, e) -> errors.resolveException(request, response, null, e)))
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(AuthProperties properties, PasswordEncoder encoder) {
        var admin1 = User.withUsername(properties.admin1Username())
                .password(encoder.encode(properties.admin1Password()))
                .roles("ADMIN")
                .build();
        var admin2 = User.withUsername(properties.admin2Username())
                .password(encoder.encode(properties.admin2Password()))
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(admin1, admin2);
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    JwtKeys jwtKeys(AuthProperties properties) {
        return JwtKeys.from(properties);
    }

    /** Decodificador do resource server: só aceita access token de sessão ativa. */
    @Bean
    JwtDecoder jwtDecoder(JwtKeys keys, AuthProperties properties, ActiveSessions sessions) {
        return TokenService.decoder(keys, properties, TokenService.typeIs(TokenService.ACCESS), sessions);
    }

    @Bean
    JwtEncoder jwtEncoder(JwtKeys keys) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keys.signing())));
    }

    /**
     * Header Authorization primeiro; sem ele, o cookie da sessão. Nos POST de login/sessão e no JWKS nenhum token é
     * lido: um cookie vencido não pode impedir o próprio login ou o refresh (o resource server responderia 401
     * antes de chegar ao controller).
     */
    @Bean
    BearerTokenResolver bearerTokenResolver() {
        var header = new DefaultBearerTokenResolver();
        return request -> {
            String uri = request.getRequestURI();
            if (JWKS.equals(uri) || ("POST".equals(request.getMethod()) && PUBLIC_AUTH_POSTS.contains(uri))) {
                return null;
            }
            String token = header.resolve(request);
            return token != null ? token : SessionCookies.read(request, SessionCookies.ACCESS);
        };
    }
}
