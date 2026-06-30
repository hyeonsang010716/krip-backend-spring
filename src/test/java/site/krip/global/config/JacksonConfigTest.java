package site.krip.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JacksonConfig} Instant 직렬화 단위 테스트 — Z(UTC) ISO-8601 형식 검증.
 * Spring 컨텍스트 없이 customizer + SNAKE_CASE 를 builder 에 직접 적용해 동일 ObjectMapper 를 재현한다.
 */
@DisplayName("Jackson 설정 — Instant UTC 직렬화·snake_case")
class JacksonConfigTest {

    private ObjectMapper mapper() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        // config 의 @Bean customizer 를 그대로 적용 (Instant 시리얼라이저 등록).
        new JacksonConfig().instantSerializationCustomizer().customize(builder);
        // application.yml 의 SNAKE_CASE 동작 재현.
        builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return builder.build();
    }

    /** Instant 1개를 단독 직렬화하면 따옴표 포함 JSON 문자열이 나오므로 벗긴다. */
    private String serializeInstant(Instant instant) throws Exception {
        String json = mapper().writeValueAsString(instant);
        assertThat(json).startsWith("\"").endsWith("\"");
        return json.substring(1, json.length() - 1);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            // 소수부 0 → Z 표기 + 소수부 없음
            "2026-07-03T12:34:56Z,            2026-07-03T12:34:56Z",
            // 0.789s = 789000 micros → .789000 (6자리)
            "2026-07-03T12:34:56.789Z,        2026-07-03T12:34:56.789000Z",
            // 나노초 정밀도는 마이크로초(6자리)로 절삭
            "2026-07-03T12:34:56.123456789Z,  2026-07-03T12:34:56.123456Z",
            // 1us 단위도 6자리로 표현
            "2026-07-03T12:34:56.000001Z,      2026-07-03T12:34:56.000001Z"})
    @DisplayName("Instant 는 Z(UTC) + 마이크로초(6자리) ISO-8601 로 직렬화된다")
    void instantSerializesAsMicrosUtc(String input, String expected) throws Exception {
        assertThat(serializeInstant(Instant.parse(input))).isEqualTo(expected);
    }

    @Test
    @DisplayName("UTC 는 항상 'Z' 접미사로 직렬화된다 (+00:00 미사용)")
    void alwaysUsesZSuffix() throws Exception {
        assertThat(serializeInstant(Instant.parse("2026-01-01T00:00:00Z")))
                .endsWith("Z").doesNotContain("+00:00");
        assertThat(serializeInstant(Instant.parse("2026-01-01T00:00:00.5Z")))
                .endsWith("Z").doesNotContain("+00:00");
    }

    @Test
    @DisplayName("SNAKE_CASE 가 레코드 필드명에 적용된다")
    void snakeCaseApplied() throws Exception {
        // given
        record Sample(String userId, Instant createdAt) {
        }
        Sample sample = new Sample("USER_1", Instant.parse("2026-07-03T12:34:56Z"));

        // when
        String json = mapper().writeValueAsString(sample);

        // then
        assertThat(json).contains("\"user_id\":\"USER_1\"");
        assertThat(json).contains("\"created_at\":\"2026-07-03T12:34:56Z\"");
        assertThat(json).doesNotContain("userId").doesNotContain("createdAt");
    }
}
