package site.krip.global.auth.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RequestIdFilter} — 안전한 X-Request-ID 만 재사용, 위험 값은 UUID 재생성(CRLF 로그 위조 방지).
 */
class RequestIdFilterTest {

    private static final String HEADER = "X-Request-ID";
    private static final String UUID_PATTERN = "[A-Za-z0-9-]{36}";

    private String runWithHeader(String headerValue) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (headerValue != null) {
            req.addHeader(HEADER, headerValue);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        new RequestIdFilter().doFilter(req, res, new MockFilterChain());
        return res.getHeader(HEADER);
    }

    @Test
    @DisplayName("안전한 헤더 값은 그대로 재사용")
    void reusesSafeHeader() throws Exception {
        assertThat(runWithHeader("trace-abc_123.4")).isEqualTo("trace-abc_123.4");
    }

    @Test
    @DisplayName("CRLF 포함 값은 거부하고 UUID 재생성 — 위조 값 미반영")
    void regeneratesOnCrlf() throws Exception {
        String out = runWithHeader("abc\r\nFAKE LOG LINE");
        assertThat(out).doesNotContain("\n").doesNotContain("\r").doesNotContain("FAKE");
        assertThat(out).matches(UUID_PATTERN);
    }

    static Stream<Arguments> regeneratingHeaders() {
        return Stream.of(
                Arguments.of("과도하게 긴 값(>64)", "a".repeat(100)),
                Arguments.of("헤더 없음", null));
    }

    @ParameterizedTest(name = "{0} → UUID 재생성")
    @MethodSource("regeneratingHeaders")
    @DisplayName("부적합/부재 헤더는 새 UUID 로 재생성된다")
    void regeneratesAsUuid(String desc, String headerValue) throws Exception {
        assertThat(runWithHeader(headerValue)).matches(UUID_PATTERN);
    }
}
