package site.krip.global.support;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Instant → ISO-8601 문자열 (UTC {@code Z} 표기, 마이크로초 6자리·0이면 소수부 생략).
 *
 * <p>시각 문자열의 단일 출처. REST 직렬화({@code JacksonConfig})와 WS 페이로드가 모두 이 포맷을 쓴다.
 * (예: {@code 2026-06-04T12:34:56.789000Z}, {@code 2026-06-04T12:34:56Z})
 */
public final class IsoTimestamp {

    // 마이크로초가 0 이 아니면 6자리 고정, 0 이면 소수부 생략. 'XXX' 는 UTC(zero offset)를 'Z' 로 출력.
    private static final DateTimeFormatter WITH_MICROS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX");
    private static final DateTimeFormatter NO_FRACTION =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private IsoTimestamp() {
    }

    public static String format(Instant instant) {
        OffsetDateTime odt = instant.atOffset(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        DateTimeFormatter fmt = odt.getNano() == 0 ? NO_FRACTION : WITH_MICROS;
        return fmt.format(odt);
    }
}
