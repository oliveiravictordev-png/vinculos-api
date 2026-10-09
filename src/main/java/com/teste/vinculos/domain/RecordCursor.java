package com.teste.vinculos.domain;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Posição na busca paginada: o último registro devolvido, na ordem (empresa, id). A página seguinte começa logo
 * depois dele, então inserir ou remover registros entre duas páginas não repete nem pula itens, ao contrário de
 * {@code skip}, que também ficaria mais lento a cada página.
 *
 * <p>Para o cliente o cursor é opaco (Base64 URL-safe): a API pode mudar o formato sem quebrar o contrato v1.
 */
public record RecordCursor(String company, long id) {

    /** Um cursor válido tem bem menos que isso; o limite barra entradas abusivas antes de decodificar. */
    public static final int MAX_LENGTH = 64;

    public static RecordCursor after(CustomerRecord last) {
        return new RecordCursor(last.company(), last.id());
    }

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((company + ":" + id).getBytes(StandardCharsets.UTF_8));
    }

    /** Cursor ausente ou em branco significa primeira página. */
    public static RecordCursor decode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > MAX_LENGTH) {
            throw invalid();
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int separator = decoded.indexOf(':');
            String company = decoded.substring(0, Math.max(separator, 0));
            if (!Documents.isValidCnpj(company)) {
                throw invalid();
            }
            return new RecordCursor(company, Long.parseLong(decoded.substring(separator + 1)));
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static InvalidDataException invalid() {
        return new InvalidDataException("invalid cursor");
    }
}
