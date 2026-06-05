package site.krip.domain.feed.dto.request;

/**
 * 캡션 변경 요청. null/빈/공백만 입력은 캡션 삭제로 처리한다(서비스가 정규화).
 *
 * <p>길이 검증은 컨트롤러의 {@code validateCaptionLength} 가 코드포인트 기준으로 수행한다
 * ({@code @Size} 는 UTF-16 코드유닛 기준이라 이모지에서 어긋나므로 사용하지 않음).
 */
public record UpdateCaptionRequest(
        String caption
) {
}
