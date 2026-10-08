package com.teste.vinculos.web;

import com.mongodb.MongoException;
import com.teste.vinculos.domain.InvalidDataException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Erros no formato RFC 9457 (application/problem+json). */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LogManager.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidDataException.class)
    ProblemDetail invalidData(InvalidDataException e) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid data");
        return problem;
    }

    // Timeout ou indisponibilidade do banco: falha explícita em vez de resposta parcial.
    @ExceptionHandler(MongoException.class)
    ProblemDetail databaseUnavailable(MongoException e) {
        log.error("MongoDB query failed (code {})", e.getCode(), e);
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Database is currently unavailable, please try again");
        problem.setTitle("Service unavailable");
        return problem;
    }
}
