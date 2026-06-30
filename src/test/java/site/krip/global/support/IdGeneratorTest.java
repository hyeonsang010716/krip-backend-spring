package site.krip.global.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link IdGenerator} 순수 단위 테스트 — 형식/접두사/고유성. Spring/DB 불필요.
 */
@DisplayName("ID 생성기 — 형식·고유성·접두사")
class IdGeneratorTest {

    // <PREFIX>_<epochSeconds>_<16 hex>
    private static final Pattern FORMAT = Pattern.compile("^([A-Z]+)_(\\d+)_([0-9a-f]{16})$");

    private static Stream<Arguments> factories() {
        return Stream.of(
                Arguments.of("USER", (Supplier<String>) IdGenerator::userId),
                Arguments.of("TS", (Supplier<String>) IdGenerator::travelStyleId),
                Arguments.of("TMP", (Supplier<String>) IdGenerator::tripmatePostId),
                Arguments.of("TMI", (Supplier<String>) IdGenerator::tripmateImageId),
                Arguments.of("FS", (Supplier<String>) IdGenerator::friendshipId),
                Arguments.of("BLK", (Supplier<String>) IdGenerator::userBlockId),
                Arguments.of("TP", (Supplier<String>) IdGenerator::tourPlanId),
                Arguments.of("TPI", (Supplier<String>) IdGenerator::tourPlanItemId),
                Arguments.of("FP", (Supplier<String>) IdGenerator::favoritePlaceId),
                Arguments.of("CR", (Supplier<String>) IdGenerator::chatRoomId),
                Arguments.of("MSG", (Supplier<String>) IdGenerator::messageId),
                Arguments.of("WS", (Supplier<String>) IdGenerator::sessionId),
                Arguments.of("FDP", (Supplier<String>) IdGenerator::feedPostId),
                Arguments.of("FDC", (Supplier<String>) IdGenerator::feedPostCommentId),
                Arguments.of("FCM", (Supplier<String>) IdGenerator::fcmTokenId));
    }

    @ParameterizedTest(name = "{0} factory -> matches format & prefix")
    @MethodSource("factories")
    @DisplayName("각 팩토리는 <PREFIX>_<epochSeconds>_<16hex> 형식과 올바른 접두사를 가진다")
    void factoryProducesCorrectFormatAndPrefix(String expectedPrefix, Supplier<String> factory) {
        long before = Instant.now().getEpochSecond();
        String id = factory.get();
        long after = Instant.now().getEpochSecond();

        var m = FORMAT.matcher(id);
        assertThat(m.matches())
                .as("id '%s' should match <PREFIX>_<epochSeconds>_<16hex>", id)
                .isTrue();
        assertThat(m.group(1)).isEqualTo(expectedPrefix);

        long ts = Long.parseLong(m.group(2));
        assertThat(ts).isBetween(before, after);

        assertThat(m.group(3)).hasSize(16).matches("[0-9a-f]{16}");
    }

    @ParameterizedTest(name = "{0} factory -> unique across calls")
    @MethodSource("factories")
    @DisplayName("각 팩토리는 반복 호출 시 고유한 ID 를 생성한다")
    void factoryProducesUniqueIds(String expectedPrefix, Supplier<String> factory) {
        int n = 1000;
        Set<String> seen = new HashSet<>(n * 2);
        for (int i = 0; i < n; i++) {
            seen.add(factory.get());
        }
        assertThat(seen).hasSize(n);
    }

    @Test
    @DisplayName("서로 다른 팩토리는 서로 다른 접두사를 사용한다")
    void distinctPrefixesAcrossFactories() {
        Set<String> prefixes = factories()
                .map(a -> (String) a.get()[0])
                .collect(java.util.stream.Collectors.toSet());
        // 모든 팩토리의 접두사가 유일해야 한다.
        assertThat(prefixes).hasSize((int) factories().count());
    }

    @Test
    @DisplayName("hex 부분은 항상 소문자 16진수 16자리이다")
    void hexSuffixIsLowercase() {
        for (int i = 0; i < 200; i++) {
            String id = IdGenerator.feedPostId();
            String hex = id.substring(id.lastIndexOf('_') + 1);
            assertThat(hex).matches("[0-9a-f]{16}");
        }
    }
}
