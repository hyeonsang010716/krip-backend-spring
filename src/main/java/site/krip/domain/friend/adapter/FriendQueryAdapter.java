package site.krip.domain.friend.adapter;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import site.krip.domain.friend.port.FriendQueryPort;
import site.krip.domain.friend.repository.FriendshipRepository;
import site.krip.domain.friend.repository.UserBlockRepository;

import java.util.Collection;
import java.util.List;

/** {@link FriendQueryPort} 구현 — friend repository 를 도메인 경계 밖 표현으로 변환. */
@Component
@RequiredArgsConstructor
public class FriendQueryAdapter implements FriendQueryPort {

    private final UserBlockRepository blockRepository;
    private final FriendshipRepository friendshipRepository;

    @Override
    @Transactional(readOnly = true)
    public List<BlockPair> findBlocksBetween(String userA, String userB) {
        return blockRepository.findBlocksBetween(userA, userB).stream()
                .map(b -> new BlockPair(b.getBlockerId(), b.getBlockedId()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> acceptedFriendIds(String meId) {
        return friendshipRepository.findAcceptedFriendIds(meId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> acceptedFriendIdsAmong(String meId, Collection<String> candidates) {
        return friendshipRepository.findAcceptedFriendIdsWith(meId, candidates);
    }
}
