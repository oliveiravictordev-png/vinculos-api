package com.teste.vinculos.web.security;

import org.springframework.security.core.AuthenticationException;

/** Cookie de sessão ausente, expirado, adulterado ou de uma sessão revogada: vira 401 no ApiExceptionHandler. */
public class InvalidSessionException extends AuthenticationException {

    public InvalidSessionException(String message) {
        super(message);
    }
}
