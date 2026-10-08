package com.teste.vinculos.web;

import com.mongodb.MongoException;
import com.teste.vinculos.domain.InvalidDataException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Único ponto que define o formato de erro da API: RFC 9457 (application/problem+json). Inclui o 429 do
 * {@link RateLimitFilter}, que chega aqui pelo HandlerExceptionResolver do Spring MVC.
 *
 * <p>Por segurança, a API não revela a própria estrutura: caminho inexistente, método errado, JSON malformado ou
 * content-type inválido viram o mesmo 400 genérico (sem 404/405/415 que ajudem a mapear rotas), e erros inesperados
 * viram 500 sem detalhe interno. A precedência máxima garante que este handler responda antes do tratamento
 * padrão do Spring Boot (spring.mvc.problemdetails), que devolveria 404/405 com mensagens próprias.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

    static final String INVALID_REQUEST = "Invalid request";

    private static final Logger log = LogManager.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidDataException.class)
    ProblemDetail invalidData(InvalidDataException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid data", e.getMessage());
    }

    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeException.class, HttpMessageNotReadableException.class})
    ProblemDetail invalidRequest(Exception e) {
        log.debug("Invalid request: {}", e.getMessage());
        return problem(HttpStatus.BAD_REQUEST, INVALID_REQUEST, INVALID_REQUEST);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ProblemDetail> rateLimitExceeded(RateLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()))
                .body(problem(HttpStatus.TOO_MANY_REQUESTS, "Too many requests", e.getMessage()));
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail unauthorized(AuthenticationException e) {
        log.debug("Authentication failed: {}", e.getClass().getSimpleName());
        return problem(HttpStatus.UNAUTHORIZED, "Unauthorized", "Invalid username or password");
    }

    // Timeout ou indisponibilidade do banco: falha explícita em vez de resposta parcial.
    @ExceptionHandler(MongoException.class)
    ProblemDetail databaseUnavailable(MongoException e) {
        log.error("MongoDB query failed (code {})", e.getCode(), e);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable", "Database is currently unavailable, please try again");
    }

    // Qualquer outra falha: registrada no log, sem expor classe, mensagem ou stack trace ao cliente.
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unexpected error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", "Unexpected error, please try again");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
