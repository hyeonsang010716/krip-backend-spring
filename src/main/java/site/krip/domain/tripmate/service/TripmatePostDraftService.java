package site.krip.domain.tripmate.service;

import org.springframework.stereotype.Service;
import site.krip.domain.tripmate.document.TripmatePostDraft;
import site.krip.domain.tripmate.dto.request.SaveDraftRequest;
import site.krip.domain.tripmate.repository.TripmatePostDraftRepository;

import java.util.Optional;

/**
 * 게시글 임시저장. 유저당 1개 upsert.
 */
@Service
public class TripmatePostDraftService {

    private final TripmatePostDraftRepository draftRepository;

    public TripmatePostDraftService(TripmatePostDraftRepository draftRepository) {
        this.draftRepository = draftRepository;
    }

    public TripmatePostDraft saveDraft(String userId, SaveDraftRequest req) {
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
