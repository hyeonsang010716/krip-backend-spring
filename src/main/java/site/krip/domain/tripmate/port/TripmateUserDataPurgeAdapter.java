package site.krip.domain.tripmate.port;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import site.krip.domain.auth.port.ExternalUserDataPurgePort;
import site.krip.domain.tripmate.repository.TripmateImageRepository;
import site.krip.domain.tripmate.repository.TripmatePostDraftRepository;
import site.krip.domain.tripmate.repository.TripmateSearchHistoryRepository;

/**
 * 탈퇴 영구 삭제 시 tripmate 의 MongoDB 유저 데이터 정리 — auth {@link ExternalUserDataPurgePort} 실구현.
 *
 * <p>tripmate 의 RDB 데이터(tripmate_post 등)는 users 행 삭제 시 FK ON DELETE CASCADE 로 정리되므로
 * 여기서는 Mongo 컬렉션 3종만 다룬다. 컬렉션별로 독립 best-effort 삭제하고, 하나라도 실패하면
 * 호출자(WithdrawService.purgeExternal)가 orphan 로그를 남기도록 마지막에 예외를 다시 던진다.
 */
@Component
public class TripmateUserDataPurgeAdapter implements ExternalUserDataPurgePort {

    private static final Logger log = LoggerFactory.getLogger(TripmateUserDataPurgeAdapter.class);

    private final TripmateImageRepository imageRepository;
    private final TripmatePostDraftRepository draftRepository;
    private final TripmateSearchHistoryRepository searchHistoryRepository;

    public TripmateUserDataPurgeAdapter(TripmateImageRepository imageRepository,
                                        TripmatePostDraftRepository draftRepository,
                                        TripmateSearchHistoryRepository searchHistoryRepository) {
        this.imageRepository = imageRepository;
        this.draftRepository = draftRepository;
        this.searchHistoryRepository = searchHistoryRepository;
    }

    @Override
    public void purgeUserMongoData(String userId) {
        int failed = 0;
        failed += tryDelete("tripmate_image", () -> imageRepository.deleteByUserId(userId), userId);
        failed += tryDelete("tripmate_post_draft", () -> draftRepository.deleteByUserId(userId), userId);
        failed += tryDelete("tripmate_search_history", () -> searchHistoryRepository.deleteAllByUserId(userId), userId);
        if (failed > 0) {
            throw new IllegalStateException(
                    "tripmate Mongo 데이터 일부 삭제 실패 (" + failed + "건, user_id=" + userId + ")");
        }
    }

    private int tryDelete(String collection, Runnable delete, String userId) {
        try {
            delete.run();
            return 0;
        } catch (Exception e) {
            log.error("탈퇴 purge — {} 삭제 실패 (user_id={})", collection, userId, e);
            return 1;
        }
    }
}
