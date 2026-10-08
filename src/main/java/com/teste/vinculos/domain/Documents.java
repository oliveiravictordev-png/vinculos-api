package com.teste.vinculos.domain;

/**
 * Normalização, validação (dígitos verificadores) e geração de CPF/CNPJ.
 * Aceita o CNPJ alfanumérico (Receita Federal, 2026): 12 posições [0-9A-Z] + 2 dígitos verificadores.
 */
public final class Documents {

    /** Maior entrada aceita: o CNPJ formatado (ex.: 12.ABC.345/01DE-35). Barra entradas abusivas antes de processar. */
    public static final int MAX_INPUT_LENGTH = 18;

    private Documents() {
    }

    /** Remove pontuação/espaços e converte para maiúsculas. */
    public static String normalize(String value) {
        if (value == null) {
            throw new InvalidDataException("document is required");
        }
        if (value.length() > MAX_INPUT_LENGTH) {
            throw new InvalidDataException("document must have at most " + MAX_INPUT_LENGTH + " characters");
        }
        var sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (isDigit(c) || (c >= 'A' && c <= 'Z')) {
                sb.append(c);
            } else if (c >= 'a' && c <= 'z') {
                sb.append((char) (c - 32));
            }
        }
        return sb.toString();
    }

    public static boolean isValid(DocumentType type, String document) {
        return switch (type) {
            case CPF -> isValidCpf(document);
            case CNPJ -> isValidCnpj(document);
        };
    }

    public static boolean isValidCpf(String document) {
        if (document == null || document.length() != 11 || allSame(document)) {
            return false;
        }
        char[] c = document.toCharArray();
        for (char ch : c) {
            if (!isDigit(ch)) {
                return false;
            }
        }
        return c[9] - '0' == cpfCheckDigit(c, 9) && c[10] - '0' == cpfCheckDigit(c, 10);
    }

    public static boolean isValidCnpj(String document) {
        if (document == null || document.length() != 14 || allSame(document)) {
            return false;
        }
        char[] c = document.toCharArray();
        for (int i = 0; i < 12; i++) {
            if (!isDigit(c[i]) && !(c[i] >= 'A' && c[i] <= 'Z')) {
                return false;
            }
        }
        if (!isDigit(c[12]) || !isDigit(c[13])) {
            return false;
        }
        return c[12] - '0' == cnpjCheckDigit(c, 12) && c[13] - '0' == cnpjCheckDigit(c, 13);
    }

    /** Gera um CPF válido a partir de uma base numérica de até 9 dígitos. */
    public static String generateCpf(long base) {
        char[] c = fill(base, 9, 11);
        c[9] = (char) ('0' + cpfCheckDigit(c, 9));
        c[10] = (char) ('0' + cpfCheckDigit(c, 10));
        return new String(c);
    }

    /** Gera um CNPJ numérico válido a partir de uma base de até 12 dígitos (raiz + filial). */
    public static String generateCnpj(long base) {
        char[] c = fill(base, 12, 14);
        c[12] = (char) ('0' + cnpjCheckDigit(c, 12));
        c[13] = (char) ('0' + cnpjCheckDigit(c, 13));
        return new String(c);
    }

    /** Mantém só os 3 primeiros e 2 últimos caracteres, para uso em logs (LGPD). */
    public static String mask(String document) {
        if (document == null || document.length() < 6) {
            return "***";
        }
        return document.substring(0, 3)
                + "*".repeat(document.length() - 5)
                + document.substring(document.length() - 2);
    }

    // Pesos 10..2 (1º DV) e 11..2 (2º DV).
    private static int cpfCheckDigit(char[] c, int n) {
        int sum = 0;
        for (int i = 0; i < n; i++) {
            sum += (c[i] - '0') * (n + 1 - i);
        }
        return checkDigit(sum);
    }

    // Pesos 2..9 da direita para a esquerda; letras valem (ASCII - 48), conforme regra do CNPJ alfanumérico.
    private static int cnpjCheckDigit(char[] c, int n) {
        int sum = 0;
        int weight = 2;
        for (int i = n - 1; i >= 0; i--) {
            sum += (c[i] - '0') * weight;
            weight = weight == 9 ? 2 : weight + 1;
        }
        return checkDigit(sum);
    }

    private static int checkDigit(int sum) {
        int remainder = sum % 11;
        return remainder < 2 ? 0 : 11 - remainder;
    }

    private static char[] fill(long value, int digits, int length) {
        char[] c = new char[length];
        for (int i = digits - 1; i >= 0; i--) {
            c[i] = (char) ('0' + value % 10);
            value /= 10;
        }
        return c;
    }

    private static boolean allSame(String s) {
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) != s.charAt(0)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
