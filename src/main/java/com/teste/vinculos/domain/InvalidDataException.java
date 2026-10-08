package com.teste.vinculos.domain;

/** Dado de entrada que viola uma regra do domínio; a web o traduz em 400 com a mensagem como detalhe. */
public class InvalidDataException extends RuntimeException {

    public InvalidDataException(String message) {
        super(message);
    }
}
