package site.krip.domain.tour.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import site.krip.domain.auth.port.ExternalUserDataPurgePort;
import site.krip.domain.tour.repository.TourSearchHistoryRepository;

/**
 * 탈퇴 영구 삭제 시 tour 의 MongoDB 유저 데이터 정리 — auth {@link ExternalUserDataPurgePort} 실구현.
 *
 * <p>tour 의 RDB 데이터(tour_plan/tour_plan_item/favorite_place)는 users 행 삭제 시 FK ON DELETE CASCADE
 * (V4/V5)로 정리되므로 여기서는 Mongo 컬렉션 {@code tour_search_history} 만 다룬다. (Place 컬렉션은 외부 시드
 * 참조 데이터라 유저 PII 가 없어 제외.) 실패 시 호출자(WithdrawService.purgeExternal)가 orphan 로그를
 * 남기도록 예외를 그대로 전파한다.
 */
@Component
public class TourUserDataPurgeAdapter implements ExternalUserDataPurgePort {

    private static final Logger log = LoggerFactory.getLogger(TourUserDataPurgeAdapter.class);

    private final TourSearchHistoryRepository searchHistoryRepository;

    public TourUserDataPurgeAdapter(TourSearchHistoryRepository searchHistoryRepository) {
        this.searchHistoryRepository = searchHistoryRepository;
    }

    @Override
    public void purgeUserMongoData(String userId) {
        searchHistoryRepository.deleteAllByUserId(userId);
        log.debug("탈퇴 purge — tour_search_history 삭제 완료 (user_id={})", userId);
    }
}
