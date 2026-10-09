package com.teste.vinculos.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordCursorTest {

    @Test
    void roundTripsThroughAnOpaqueUrlSafeString() {
        var record = new CustomerRecord(702879419L, "10007037000103", "SEGURO", BigDecimal.ONE, Instant.EPOCH);
        String encoded = RecordCursor.after(record).encode();

        assertThat(encoded).matches("[A-Za-z0-9_-]+").doesNotContain("10007037000103");
        assertThat(RecordCursor.decode(encoded)).isEqualTo(new RecordCursor("10007037000103", 702879419L));
    }

    @Test
    void blankMeansFirstPage() {
        assertThat(RecordCursor.decode(null)).isNull();
        assertThat(RecordCursor.decode("")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not base64!", "MTIz", "aW52YWxpZDox", "MTAwMDcwMzcwMDAxMDM6YWJj"})
    void rejectsTamperedCursors(String cursor) {
        assertThatThrownBy(() -> RecordCursor.decode(cursor))
                .isInstanceOf(InvalidDataException.class).hasMessage("invalid cursor");
    }

    @Test
    void rejectsOversizedCursorBeforeDecoding() {
        String huge = Base64.getUrlEncoder().encodeToString("x".repeat(100).getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> RecordCursor.decode(huge)).isInstanceOf(InvalidDataException.class);
    }
}
