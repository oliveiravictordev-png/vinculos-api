package com.teste.vinculos.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.ThreadContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * ID de correlação da requisição: vai no cabeçalho {@value #HEADER} da resposta, em todo log da requisição
 * ({@code %X{requestId}}) e no registro de auditoria. Com ele, um usuário que reporta um erro aponta exatamente a
 * linha de log e a auditoria correspondentes.
 *
 * <p>Aceita o ID vindo do cliente ou de um proxy só se tiver formato seguro (evita injeção de texto em log);
 * senão gera um UUID. Roda logo depois dos cabeçalhos de segurança, antes da autenticação e do rate limit, para
 * que 401 e 429 também tenham ID.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    static final String ATTRIBUTE = RequestIdFilter.class.getName();
    static final String LOG_KEY = "requestId";

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String received = request.getHeader(HEADER);
        String id = received != null && SAFE_ID.matcher(received).matches() ? received : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader(HEADER, id);
        ThreadContext.put(LOG_KEY, id);
        try {
            chain.doFilter(request, response);
        } finally {
            ThreadContext.remove(LOG_KEY);
        }
    }

    /** ID da requisição atual ({@code null} fora do filtro, como em testes de fatia sem filtros). */
    public static String current(HttpServletRequest request) {
        return (String) request.getAttribute(ATTRIBUTE);
    }
}
