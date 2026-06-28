package site.krip.domain.friend.adapter;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import site.krip.domain.auth.port.FriendCountPort;
import site.krip.domain.friend.repository.FriendshipRepository;

/**
 * auth 의 {@link FriendCountPort} 실제 구현 — friend 도메인이 제공.
 * 마이페이지 통계의 ACCEPTED 친구 수를 집계한다.
 */
@Component
@RequiredArgsConstructor
public class FriendCountAdapter implements FriendCountPort {

    private final FriendshipRepository friendshipRepository;

    @Override
    @Transactional(readOnly = true)
    public long countAcceptedFriends(String userId) {
        return friendshipRepository.countAcceptedForUser(userId);
    }
}
