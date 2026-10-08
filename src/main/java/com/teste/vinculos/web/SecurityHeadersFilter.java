package com.teste.vinculos.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Cabeçalhos de segurança em todas as respostas (inclusive erros e 429, por isso roda antes dos demais filtros).
 *
 * <ul>
 *   <li>Respostas de /api trazem dados pessoais: {@code Cache-Control: no-store} impede cópia em navegador ou proxy,
 *       e a CSP mais restrita possível vale porque são só JSON.</li>
 *   <li>O Swagger UI precisa carregar o próprio JavaScript e CSS, então recebe uma CSP limitada à própria origem.</li>
 *   <li>HSTS só quando a requisição chegou por HTTPS (vista pelo proxy reverso).</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    static final String API_CSP = "default-src 'none'; frame-ancestors 'none'";
    static final String DOCS_CSP = "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        response.setHeader("Content-Security-Policy", isDocs(request.getRequestURI()) ? DOCS_CSP : API_CSP);
        if (request.getRequestURI().startsWith("/api/")) {
            response.setHeader("Cache-Control", "no-store");
        }
        if (request.isSecure()) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
        chain.doFilter(request, response);
    }

    private static boolean isDocs(String uri) {
        return uri.startsWith("/swagger-ui") || uri.startsWith("/v3/api-docs");
    }
}
