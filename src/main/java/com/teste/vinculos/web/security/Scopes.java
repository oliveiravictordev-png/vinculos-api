package com.teste.vinculos.web.security;

import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.List;

/**
 * Permissões que vão no claim {@code scope} do access token, uma por grupo de endpoints. O papel do usuário vira
 * escopos aqui, num lugar só; hoje os dois administradores têm todos.
 */
final class Scopes {

    static final String CUSTOMERS_READ = "customers:read";
    static final String CUSTOMERS_EXPORT = "customers:export";
    static final String AUDIT_READ = "audit:read";

    private static final List<String> ADMIN = List.of(CUSTOMERS_READ, CUSTOMERS_EXPORT, AUDIT_READ);

    private Scopes() {
    }

    static List<String> of(Collection<? extends GrantedAuthority> authorities) {
        boolean admin = authorities.stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        return admin ? ADMIN : List.of();
    }

    /** Autoridade que o Spring Security cria para cada escopo do token. */
    static String authority(String scope) {
        return "SCOPE_" + scope;
    }
}
