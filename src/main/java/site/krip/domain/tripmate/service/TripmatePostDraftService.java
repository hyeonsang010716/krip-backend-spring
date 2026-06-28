package site.krip.domain.tripmate.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import site.krip.domain.tripmate.document.TripmatePostDraft;
import site.krip.domain.tripmate.dto.request.SaveDraftRequest;
import site.krip.domain.tripmate.repository.TripmatePostDraftRepository;

import java.util.Optional;

/**
 * 게시글 임시저장. 유저당 1개 upsert.
 */
@Service
@RequiredArgsConstructor
public class TripmatePostDraftService {

    private final TripmatePostDraftRepository draftRepository;
    private final TripmateImageOwnershipValidator imageOwnershipValidator;

    public TripmatePostDraft saveDraft(String userId, SaveDraftRequest req) {
        // 첨부 이미지 URL 은 본인 업로드분만 허용 (타인 이미지 URL 주입 방지).
        imageOwnershipValidator.verify(userId, req.imageUrls());
        return draftRepository.upsert(
                userId, req.title(), req.content(),
                req.preferredAgeMin(), req.preferredAgeMax(), req.preferredGender(),
                req.region(), req.travelStartDate(), req.travelEndDate(), req.companionType(),
                req.imageUrls());
    }

    public Optional<TripmatePostDraft> getDraft(String userId) {
        return draftRepository.findByUserId(userId);
    }

    public void deleteDraft(String userId) {
        draftRepository.deleteByUserId(userId);
    }
}
