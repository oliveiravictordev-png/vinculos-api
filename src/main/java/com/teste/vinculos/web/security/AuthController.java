package com.teste.vinculos.web.security;

import com.teste.vinculos.domain.InvalidDataException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Login e sessão. Dois fluxos, com a mesma validação de usuário:
 *
 * <ul>
 *   <li><b>API</b> ({@code POST /token}): devolve o access token no corpo, para Postman e integrações.</li>
 *   <li><b>Navegador</b> ({@code /session}, {@code /refresh}, {@code /logout}): o token só trafega em cookies
 *       HttpOnly e nunca aparece no corpo, então o JavaScript da página (e um eventual XSS) não tem acesso a ele.</li>
 * </ul>
 * Toda sessão fica registrada no banco: o logout a revoga em todas as instâncias da API.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Profile("!seed")
@Tag(name = "Authentication", description = "Login, sessão do navegador e chaves públicas dos tokens")
@ApiResponse(responseCode = "401", description = "Usuário ou senha inválidos, ou sessão ausente, expirada ou revogada",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserDetailsService users;
    private final TokenService tokens;
    private final ActiveSessions sessions;
    private final JwtKeys keys;
    private final AuthProperties properties;
    private final Clock clock;

    public AuthController(AuthenticationManager authenticationManager, UserDetailsService users, TokenService tokens,
                          ActiveSessions sessions, JwtKeys keys, AuthProperties properties, Clock clock) {
        this.authenticationManager = authenticationManager;
        this.users = users;
        this.tokens = tokens;
        this.sessions = sessions;
        this.keys = keys;
        this.properties = properties;
        this.clock = clock;
    }

    @PostMapping("/token")
    @Operation(summary = "Autentica e emite um access token (Bearer)",
            description = "Para Postman e integrações. O token vale JWT_TTL e não é renovável: peça outro ao vencer.")
    @ApiResponse(responseCode = "200", description = "Access token no corpo")
    public TokenResponse token(@RequestBody LoginRequest request) {
        Authentication user = authenticate(request);
        String sessionId = sessions.create(user.getName(), clock.instant().plus(properties.ttl()));
        var access = tokens.issueAccess(user.getName(), Scopes.of(user.getAuthorities()), sessionId);
        return new TokenResponse(access.value(), "Bearer", access.expiresAt());
    }

    @PostMapping("/session")
    @Operation(summary = "Login do navegador: abre a sessão em cookies HttpOnly",
            description = "Grava o access token (curto) e o refresh token (até JWT_REFRESH_TTL) em cookies HttpOnly, "
                    + "Secure e SameSite=Strict. O corpo não traz token.")
    @ApiResponse(responseCode = "200", description = "Sessão aberta")
    public ResponseEntity<SessionResponse> login(@RequestBody LoginRequest request) {
        Authentication user = authenticate(request);
        Instant sessionExpiresAt = clock.instant().plus(properties.refreshTtl());
        String sessionId = sessions.create(user.getName(), sessionExpiresAt);
        return withCookies(user.getName(), Scopes.of(user.getAuthorities()), sessionId, sessionExpiresAt);
    }

    @GetMapping("/session")
    @Operation(summary = "Usuário da sessão atual", description = "Usado pelo front para saber se já existe sessão.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Sessão válida")
    public SessionResponse session(JwtAuthenticationToken authentication) {
        Jwt jwt = authentication.getToken();
        List<String> scopes = List.of(jwt.getClaimAsString("scope").split(" "));
        return new SessionResponse(jwt.getSubject(), scopes, jwt.getExpiresAt());
    }

    @PostMapping("/refresh")
    @Operation(summary = "Renova o access token a partir do cookie de refresh",
            description = "Emite novos cookies. Recusa sessão revogada, vencida ou de usuário que deixou de existir.")
    @ApiResponse(responseCode = "200", description = "Sessão renovada")
    public ResponseEntity<SessionResponse> refresh(HttpServletRequest request) {
        Jwt refresh = tokens.decodeRefresh(requireCookie(request));
        String username = refresh.getSubject();
        String sessionId = refresh.getClaimAsString(TokenService.SESSION_ID);
        if (!sessions.isActiveNow(sessionId, username)) {
            throw new InvalidSessionException("Session is no longer active");
        }
        try {
            var user = users.loadUserByUsername(username);
            return withCookies(username, Scopes.of(user.getAuthorities()), sessionId, refresh.getExpiresAt());
        } catch (UsernameNotFoundException e) {
            throw new InvalidSessionException("User no longer exists");
        }
    }

    @PostMapping("/logout")
    @Operation(summary = "Encerra a sessão", description = "Revoga a sessão no banco e apaga os cookies. Idempotente.")
    @ApiResponse(responseCode = "204", description = "Sessão encerrada")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        String cookie = SessionCookies.read(request, SessionCookies.REFRESH);
        if (cookie != null) {
            try {
                Jwt refresh = tokens.decodeRefresh(cookie);
                sessions.revoke(refresh.getClaimAsString(TokenService.SESSION_ID), refresh.getSubject());
            } catch (InvalidSessionException e) {
                // Cookie vencido ou adulterado: não há sessão válida para revogar; os cookies são apagados mesmo assim.
            }
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, SessionCookies.clearAccess().toString())
                .header(HttpHeaders.SET_COOKIE, SessionCookies.clearRefresh().toString())
                .build();
    }

    @GetMapping("/jwks")
    @Operation(summary = "Chaves públicas dos tokens (JWKS, RFC 7517)",
            description = "Outro serviço valida os tokens da API com estas chaves, escolhendo pelo kid do token.")
    @ApiResponse(responseCode = "200", description = "Chaves públicas RSA")
    public Map<String, Object> jwks() {
        return keys.publicKeys().toJSONObject(true);
    }

    private Authentication authenticate(LoginRequest request) {
        if (request.username() == null || request.username().isBlank()
                || request.password() == null || request.password().isBlank()) {
            throw new InvalidDataException("Username and password are required");
        }
        return authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
    }

    private ResponseEntity<SessionResponse> withCookies(String username, List<String> scopes, String sessionId,
                                                        Instant sessionExpiresAt) {
        Instant now = clock.instant();
        var access = tokens.issueAccess(username, scopes, sessionId);
        var refresh = tokens.issueRefresh(username, sessionId, sessionExpiresAt);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, SessionCookies.access(access.value(), access.expiresAt(), now).toString())
                .header(HttpHeaders.SET_COOKIE, SessionCookies.refresh(refresh.value(), refresh.expiresAt(), now).toString())
                .body(new SessionResponse(username, scopes, access.expiresAt()));
    }

    private static String requireCookie(HttpServletRequest request) {
        String cookie = SessionCookies.read(request, SessionCookies.REFRESH);
        if (cookie == null) {
            throw new InvalidSessionException("Missing refresh cookie");
        }
        return cookie;
    }

    public record LoginRequest(String username, String password) {

        // Nunca imprime a senha (toString padrão de record imprimiria).
        @Override
        public String toString() {
            return "LoginRequest[username=" + username + "]";
        }
    }

    public record TokenResponse(String accessToken, String tokenType, Instant expiresAt) {
    }

    /** Sessão sem o token: o front só precisa saber quem está logado, o que pode fazer e até quando. */
    public record SessionResponse(String username, List<String> scopes, Instant expiresAt) {
    }
}
