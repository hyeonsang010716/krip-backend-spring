package site.krip.global.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LogSafe} — CRLF/제어문자 무력화(CWE-117).
 */
class LogSafeTest {

    @Test
    @DisplayName("CR/LF 는 '_' 로 치환 — 로그 위조 방지")
    void stripsCrlf() {
        String input = "ab" + '\r' + '\n' + "FAKE LOGIN";
        assertThat(LogSafe.clean(input)).isEqualTo("ab__FAKE LOGIN");
    }

    @Test
    @DisplayName("탭은 치환, 일반 공백(0x20)은 보존")
    void stripsControlKeepsSpace() {
        String input = "a" + '\t' + "b" + ' ' + "c";
        assertThat(LogSafe.clean(input)).isEqualTo("a_b c");
    }

    @Test
    @DisplayName("일반 문자열·유니코드는 그대로")
    void passesNormalText() {
        assertThat(LogSafe.clean("맛집 검색 emoji 😀")).isEqualTo("맛집 검색 emoji 😀");
    }

    @Test
    @DisplayName("null 통과")
    void nullPassthrough() {
        assertThat(LogSafe.clean(null)).isNull();
    }
}
