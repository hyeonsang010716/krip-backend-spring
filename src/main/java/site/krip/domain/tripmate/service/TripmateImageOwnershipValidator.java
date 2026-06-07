package site.krip.domain.tripmate.service;

import org.springframework.stereotype.Component;
import site.krip.domain.tripmate.exception.PostAccessDeniedException;
import site.krip.domain.tripmate.repository.TripmateImageRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 첨부 이미지 URL 이 모두 본인 업로드분인지 검증 — 타인 URL 주입(IDOR) 및 교차 삭제 차단.
 */
@Component
public class TripmateImageOwnershipValidator {

    private final TripmateImageRepository imageRepository;

    public TripmateImageOwnershipValidator(TripmateImageRepository imageRepository) {
        this.imageRepository = imageRepository;
    }

    /** urls 가 모두 본인 소유가 아니면 {@link PostAccessDeniedException}(403). null/빈 목록은 통과. */
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
