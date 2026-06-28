package site.krip.domain.friend.adapter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import site.krip.domain.auth.port.ExternalUserDataPurgePort;
import site.krip.domain.friend.repository.FriendSearchHistoryRepository;

/**
 * 탈퇴 영구 삭제 시 friend 의 MongoDB 유저 데이터 정리 — auth {@link ExternalUserDataPurgePort} 실구현.
 *
 * <p>friend 의 RDB 데이터(friendship/user_block)는 users 행 삭제 시 FK ON DELETE CASCADE 로 정리되므로
 * 여기서는 Mongo 컬렉션 {@code friend_search_history} 만 다룬다. 실패 시 호출자(WithdrawService.purgeExternal)가
 * orphan 로그를 남기도록 예외를 그대로 전파한다.
 */
@Component
@Slf4j
public class FriendUserDataPurgeAdapter implements ExternalUserDataPurgePort {

    private final FriendSearchHistoryRepository searchHistoryRepository;

    public FriendUserDataPurgeAdapter(FriendSearchHistoryRepository searchHistoryRepository) {
        this.searchHistoryRepository = searchHistoryRepository;
    }

    @Override
    public void purgeUserMongoData(String userId) {
        searchHistoryRepository.deleteAllByUserId(userId);
        log.debug("탈퇴 purge — friend_search_history 삭제 완료 (user_id={})", userId);
    }
}
