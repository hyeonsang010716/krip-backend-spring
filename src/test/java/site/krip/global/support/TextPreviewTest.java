package site.krip.global.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TextPreview} 단위 테스트 — 코드포인트 경계 truncation 과 surrogate 미분리 회귀.
 */
@DisplayName("텍스트 프리뷰 — 코드포인트 절단·surrogate 무분할")
class TextPreviewTest {

    private static final String EMOJI = "😀"; // 😀 U+1F600 (surrogate pair, 2 char / 1 code point)

    private static boolean hasLoneSurrogate(String s) {
        return s.codePoints().anyMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF);
    }

    @Test
    @DisplayName("한도 이하 ASCII 는 원본 그대로(말줄임표 없음)")
    void shortAsciiUnchanged() {
        assertThat(TextPreview.truncate("hello", 100, "...")).isEqualTo("hello");
    }

    @Test
    @DisplayName("정확히 한도 길이는 원본 그대로")
    void exactlyLimitUnchanged() {
        String s = "a".repeat(100);
        assertThat(TextPreview.truncate(s, 100, "...")).isEqualTo(s);
    }

    @Test
    @DisplayName("한도 초과 ASCII 는 한도까지 자르고 말줄임표 부착")
    void longAsciiTruncated() {
        String s = "a".repeat(150);
        assertThat(TextPreview.truncate(s, 100, "...")).isEqualTo("a".repeat(100) + "...");
    }

    @Test
    @DisplayName("경계에서 surrogate pair 를 쪼개지 않는다 — 고립 surrogate 무발생(회귀)")
    void doesNotSplitSurrogateAtBoundary() {
        // "ab😀😀" — 옛 substring(0,3) 은 char[2]=상위 surrogate 만 포함해 깨졌다.
        String content = "ab" + EMOJI + EMOJI;
        String result = TextPreview.truncate(content, 3, "…");

        assertThat(result).isEqualTo("ab" + EMOJI + "…"); // 3 코드포인트(a,b,😀) + 말줄임표
        assertThat(hasLoneSurrogate(result)).isFalse();
        assertThat(result.codePointCount(0, result.length())).isEqualTo(4); // a,b,😀,…
    }

    @Test
    @DisplayName("순수 이모지 한도 초과 — 코드포인트 경계로 잘리고 고립 surrogate 무발생")
    void allEmojiTruncatedOnCodePointBoundary() {
        String content = EMOJI.repeat(150);           // 150 코드포인트(300 char)
        String result = TextPreview.truncate(content, 100, "…");

        assertThat(hasLoneSurrogate(result)).isFalse();
        assertThat(result).isEqualTo(EMOJI.repeat(100) + "…");
    }

    @Test
    @DisplayName("코드 유닛은 한도 초과지만 코드포인트는 한도 이하면 자르지 않음")
    void manyCodeUnitsButFewCodePointsUnchanged() {
        String content = EMOJI.repeat(60); // 120 char > 100, 그러나 60 코드포인트 ≤ 100
        assertThat(TextPreview.truncate(content, 100, "…")).isEqualTo(content);
    }

    @Test
    @DisplayName("null 은 그대로 통과")
    void nullPassthrough() {
        assertThat(TextPreview.truncate(null, 100, "...")).isNull();
    }
}
