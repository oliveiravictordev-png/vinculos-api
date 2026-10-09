package com.teste.vinculos.web.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;

import java.time.Duration;
import java.time.Instant;

/**
 * Cookies da sessão do navegador. Todos HttpOnly (JavaScript não lê o token, então um XSS não o rouba), Secure e
 * SameSite=Strict (o navegador não os envia em requisição vinda de outro site, o que protege contra CSRF; o front
 * chama /api pelo próprio domínio, via rewrite da Vercel, então para o navegador é a mesma origem).
 *
 * <ul>
 *   <li>{@value #ACCESS}: prefixo {@code __Host-} (sem Domain, Path=/), vai em toda chamada à API.</li>
 *   <li>{@value #REFRESH}: Path={@value #REFRESH_PATH}, só chega aos endpoints de sessão.</li>
 * </ul>
 */
final class SessionCookies {

    static final String ACCESS = "__Host-vinculos_access";
    static final String REFRESH = "__Secure-vinculos_refresh";
    static final String REFRESH_PATH = "/api/v1/auth";

    private SessionCookies() {
    }

    static ResponseCookie access(String token, Instant expiresAt, Instant now) {
        return cookie(ACCESS, token, "/", Duration.between(now, expiresAt));
    }

    static ResponseCookie refresh(String token, Instant expiresAt, Instant now) {
        return cookie(REFRESH, token, REFRESH_PATH, Duration.between(now, expiresAt));
    }

    static ResponseCookie clearAccess() {
        return cookie(ACCESS, "", "/", Duration.ZERO);
    }

    static ResponseCookie clearRefresh() {
        return cookie(REFRESH, "", REFRESH_PATH, Duration.ZERO);
    }

    /** Valor do cookie, ou {@code null} se a requisição não o trouxe. */
    static String read(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static ResponseCookie cookie(String name, String value, String path, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(path)
                .maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge)
                .build();
    }
}
