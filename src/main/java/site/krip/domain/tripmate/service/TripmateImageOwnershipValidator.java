package site.krip.domain.tripmate.service;

import org.springframework.stereotype.Component;
import site.krip.domain.tripmate.exception.PostAccessDeniedException;
import site.krip.domain.tripmate.repository.TripmateImageRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 게시글/임시저장에 첨부하는 이미지 URL 이 모두 본인이 업로드한 것인지 검증한다.
 *
 * <p>업로드 이미지는 {@code tripmate_image}(MongoDB)에 {@code user_id} 와 함께 기록된다.
 * 이 검증이 없으면 요청 바디의 {@code imageUrls} 로 타인/외부 이미지 URL 을 주입할 수 있고,
 * 수정 시 제거된 URL 정리(delete)와 맞물려 타인 이미지가 교차 삭제될 수 있다(IDOR).
 */
@Component
public class TripmateImageOwnershipValidator {

    private final TripmateImageRepository imageRepository;

    public TripmateImageOwnershipValidator(TripmateImageRepository imageRepository) {
        this.imageRepository = imageRepository;
    }

    /**
     * {@code urls} 가 모두 {@code userId} 소유가 아니면 {@link PostAccessDeniedException}(403).
     * null/빈 목록은 통과한다.
     */
    public void verify(String userId, List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return;
        }
        Set<String> requested = new HashSet<>(urls);
        Set<String> owned = imageRepository.findOwnedUrls(userId, requested);
        if (!owned.containsAll(requested)) {
            throw new PostAccessDeniedException("본인이 업로드하지 않은 이미지는 첨부할 수 없습니다.");
        }
    }
}
