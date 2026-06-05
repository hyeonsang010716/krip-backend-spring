package site.krip.global.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import io.swagger.v3.core.jackson.ModelResolver;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import site.krip.global.support.IsoTimestamp;

import java.io.IOException;
import java.time.Instant;

/**
 * 모든 {@link Instant} 응답을 고정 형식으로 직렬화한다.
 *
 * <p>형식: {@code +00:00} 오프셋 + 마이크로초 6자리(0이면 소수부 생략)
 * (예: {@code 2026-07-03T12:34:56.789000+00:00}, {@code 2026-07-03T12:34:56+00:00}).
 * Jackson 기본은 {@code Z} 로 출력하므로 Instant 시리얼라이저만 교체하고 나머지 설정은 Boot 자동구성을 따른다.
 */
@Configuration
public class JacksonConfig {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer instantSerializationCustomizer() {
        return builder -> builder.serializerByType(Instant.class, new InstantSerializer());
    }

    /**
     * springdoc 스키마 생성에 앱 ObjectMapper 를 쓰게 한다.
     *
     * <p>springdoc 기본 ObjectMapper 는 {@code spring.jackson.*}(SNAKE_CASE)를 따르지 않아
     * 응답은 snake_case 인데 문서만 camelCase 로 어긋난다. 앱 ObjectMapper 를 주입해 일치시킨다.
     */
    @Bean
    ModelResolver modelResolver(ObjectMapper objectMapper) {
        return new ModelResolver(objectMapper);
    }

    static final class InstantSerializer extends JsonSerializer<Instant> {

        @Override
        public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeString(IsoTimestamp.format(value));
        }
    }
}
