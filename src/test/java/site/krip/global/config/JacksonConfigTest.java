package site.krip.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JacksonConfig} 의 Instant 직렬화 단위 테스트 — Z(UTC) 표기 ISO-8601 형식 검증.
 *
 * <p>Spring 컨텍스트 없이 {@link Jackson2ObjectMapperBuilder} 에 config 의 customizer 를 적용해
 * 동일한 ObjectMapper 를 만든다. SNAKE_CASE 는 Boot 자동구성(application.yml)에서 적용되는
 * 동작이므로 동일하게 builder 에 설정해 함께 검증한다.
 */
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

    @Test
    @DisplayName("소수부가 0 인 Instant 는 Z 표기 + 소수부 없이 직렬화된다")
    void zeroFractionInstant() throws Exception {
        // 2026-07-03T12:34:56Z (마이크로초 0)
        Instant instant = Instant.parse("2026-07-03T12:34:56Z");
        assertThat(serializeInstant(instant)).isEqualTo("2026-07-03T12:34:56Z");
    }

    @Test
    @DisplayName("마이크로초가 있는 Instant 는 6자리 소수부 + Z 로 직렬화된다")
    void microsecondInstant() throws Exception {
        // 0.789 sec = 789000 micros -> .789000
        Instant instant = Instant.parse("2026-07-03T12:34:56.789Z");
        assertThat(serializeInstant(instant)).isEqualTo("2026-07-03T12:34:56.789000Z");
    }

    @Test
    @DisplayName("나노초 정밀도는 마이크로초(6자리)로 절삭된다")
    void nanosecondsTruncatedToMicros() throws Exception {
        // .123456789 -> truncate to micros -> .123456
        Instant instant = Instant.parse("2026-07-03T12:34:56.123456789Z");
        assertThat(serializeInstant(instant)).isEqualTo("2026-07-03T12:34:56.123456Z");
    }

    @Test
    @DisplayName("마이크로초 1단위(1us)도 6자리로 표현된다")
    void singleMicrosecond() throws Exception {
        Instant instant = Instant.parse("2026-07-03T12:34:56.000001Z");
        assertThat(serializeInstant(instant)).isEqualTo("2026-07-03T12:34:56.000001Z");
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
        record Sample(String userId, Instant createdAt) {
        }
        Sample sample = new Sample("USER_1", Instant.parse("2026-07-03T12:34:56Z"));
        String json = mapper().writeValueAsString(sample);

        assertThat(json).contains("\"user_id\":\"USER_1\"");
        assertThat(json).contains("\"created_at\":\"2026-07-03T12:34:56Z\"");
        assertThat(json).doesNotContain("userId").doesNotContain("createdAt");
    }
}
