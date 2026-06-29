package site.krip.global.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import site.krip.global.common.exception.ApiException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** keyset 커서 코덱 — 라운드트립(정렬키+id) 과 형식 오류 검증. */
class KeysetCursorTest {

    @Test
    @DisplayName("encode↔decode 라운드트립 — id 에 '_' 가 있어도 첫 '_' 로만 분리해 보존")
    void encodeDecodeRoundTrip() {
        Instant t = Instant.parse("2024-01-02T03:04:05.123Z");

        KeysetCursor.Decoded d = KeysetCursor.decode(KeysetCursor.encode(t, "fr_abc_123"));

        assertThat(d.sortKey()).isEqualTo(t);
        assertThat(d.id()).isEqualTo("fr_abc_123");
    }

    @ParameterizedTest(name = "\"{0}\" -> 400")
    @NullSource
    @ValueSource(strings = {"no-separator", "_id-only", "ts-only_", "notatimestamp_id"})
    @DisplayName("형식 오류 커서 → 400(ApiException)")
    void malformedCursorThrows(String cursor) {
        assertThatThrownBy(() -> KeysetCursor.decode(cursor)).isInstanceOf(ApiException.class);
    }
}
