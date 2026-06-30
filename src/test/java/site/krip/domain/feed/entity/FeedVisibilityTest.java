package site.krip.domain.feed.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FeedVisibility} 순수 매핑 헬퍼 테스트 — JSON value <-> enum, NAME 허용, 무효값 거절.
 */
@DisplayName("Visibility enum — JSON/DB 값 변환·round-trip")
class FeedVisibilityTest {

    @ParameterizedTest(name = "value '{0}' -> {1}")
    @CsvSource({
            "private, PRIVATE",
            "friends, FRIENDS",
            "public,  PUBLIC"
    })
    @DisplayName("JSON value(소문자)로부터 enum 으로 변환된다")
    void fromJsonValue(String value, FeedVisibility expected) {
        assertThat(FeedVisibility.from(value)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "NAME '{0}' -> {1}")
    @CsvSource({
            "PRIVATE, PRIVATE",
            "FRIENDS, FRIENDS",
            "PUBLIC,  PUBLIC"
    })
    @DisplayName("DB NAME(대문자)로부터도 enum 으로 변환된다")
    void fromEnumName(String name, FeedVisibility expected) {
        assertThat(FeedVisibility.from(name)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Public", "PRIV", "unknown", "0"})
    @DisplayName("알 수 없는 값은 IllegalArgumentException 을 던진다")
    void unknownValueThrows(String bad) {
        assertThatThrownBy(() -> FeedVisibility.from(bad))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("getValue 는 JSON 직렬화용 소문자 값을 반환한다")
    void getValueReturnsLowercase() {
        assertThat(FeedVisibility.PRIVATE.getValue()).isEqualTo("private");
        assertThat(FeedVisibility.FRIENDS.getValue()).isEqualTo("friends");
        assertThat(FeedVisibility.PUBLIC.getValue()).isEqualTo("public");
    }

    @Test
    @DisplayName("value <-> from 라운드트립이 모든 상수에서 성립한다")
    void roundTripAllConstants() {
        for (FeedVisibility v : FeedVisibility.values()) {
            assertThat(FeedVisibility.from(v.getValue())).isEqualTo(v);
        }
    }
}
