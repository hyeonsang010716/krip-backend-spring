package site.krip.global.support;

/**
 * 코드포인트 경계를 보존하는 미리보기 truncation.
 *
 * <p>{@code String.substring} 은 UTF-16 코드 유닛 인덱스라 이모지(surrogate pair) 중간을 잘라
 * 고립 surrogate(깨진 글자)를 남길 수 있다 — 푸시/인박스처럼 사용자에게 보이는 미리보기는 코드포인트
 * 단위로 잘라 이를 방지한다. 입력 검증의 {@code @CodePointSize} 와 같은 "글자=코드포인트" 기준을 따른다.
 */
public final class TextPreview {

    private TextPreview() {
    }

    /**
     * {@code content} 를 최대 {@code maxCodePoints} 코드포인트로 자르고, 잘렸으면 {@code ellipsis} 를 덧붙인다.
     * surrogate pair 를 쪼개지 않으며, 한도 이하면 원본을 그대로 반환한다. {@code null} 은 그대로 통과.
     */
    public static String truncate(String content, int maxCodePoints, String ellipsis) {
        // 빠른 경로: 코드 유닛 수 ≤ 한도면 코드포인트 수도 ≤ 한도라 자를 필요 없음.
        if (content == null || content.length() <= maxCodePoints) {
            return content;
        }
        // 코드 유닛은 많아도 코드포인트(이모지 등)는 한도 이하일 수 있음 — 이 경우도 자르지 않음.
        if (content.codePointCount(0, content.length()) <= maxCodePoints) {
            return content;
        }
        int end = content.offsetByCodePoints(0, maxCodePoints); // 정확히 코드포인트 경계 → surrogate 미분리
        return content.substring(0, end) + ellipsis;
    }
}
