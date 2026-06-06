package site.krip.domain.friend.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import site.krip.domain.auth.entity.User;
import site.krip.domain.auth.repository.UserRepository;
import site.krip.domain.friend.dto.response.FriendPeerResponse;
import site.krip.domain.friend.dto.response.UserBlockListResponse;
import site.krip.domain.friend.dto.response.UserBlockResponse;
import site.krip.domain.friend.entity.UserBlock;
import site.krip.domain.friend.port.BlockCachePort;
import site.krip.domain.friend.repository.FriendshipRepository;
import site.krip.domain.friend.repository.UserBlockRepository;
import site.krip.global.common.exception.ApiException;

import java.time.Instant;
import java.util.List;

/**
 * 유저 차단/해제/목록.
 *
 * <p>차단 시 두 유저 간 friendship(방향 무관)을 모두 정리한다. 차단 INSERT 는 uq_user_block_pair
 * 경합 가능 → 충돌 시 재조회. chat 차단 캐시 무효화는 포트로 위임.
 */
@Service
public class UserBlockService {

    private static final int PAGE_SIZE = 30;
    private static final Sort PAGE_SORT =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("blockId"));

    private final UserBlockRepository userBlockRepository;
    private final FriendshipRepository friendshipRepository;
    private final UserRepository userRepository;
    private final BlockCachePort blockCachePort;
    private final TransactionTemplate txTemplate;

    public UserBlockService(UserBlockRepository userBlockRepository,
                            FriendshipRepository friendshipRepository,
                            UserRepository userRepository,
                            BlockCachePort blockCachePort,
                            TransactionTemplate txTemplate) {
        this.userBlockRepository = userBlockRepository;
        this.friendshipRepository = friendshipRepository;
        this.userRepository = userRepository;
        this.blockCachePort = blockCachePort;
        this.txTemplate = txTemplate;
    }

    public UserBlockResponse blockUser(String userId, String targetUserId) {
        if (userId.equals(targetUserId)) {
            throw ApiException.badRequest("자기 자신을 차단할 수 없습니다.");
        }
        try {
            return txTemplate.execute(s -> doBlock(userId, targetUserId));
        } catch (DataIntegrityViolationException e) {
            return txTemplate.execute(s -> {
                if (userBlockRepository.existsByBlockerIdAndBlockedId(userId, targetUserId)) {
                    throw ApiException.badRequest("이미 차단한 유저입니다.");
                }
                throw ApiException.badRequest("차단을 처리하지 못했습니다. 잠시 후 다시 시도해주세요.");
            });
        }
    }

    private UserBlockResponse doBlock(String userId, String targetUserId) {
        User target = userRepository.findByIdWithProfile(targetUserId)
                .orElseThrow(() -> ApiException.badRequest("존재하지 않는 유저입니다."));

        if (userBlockRepository.existsByBlockerIdAndBlockedId(userId, targetUserId)) {
            throw ApiException.badRequest("이미 차단한 유저입니다.");
        }

        // 두 유저 간 friendship(방향 무관) 정리 — PENDING/ACCEPTED 즉시 단절, REJECTED 도 삭제
        friendshipRepository.findBetween(userId, targetUserId).ifPresent(f -> {
            friendshipRepository.delete(f);
            friendshipRepository.flush();
        });

        UserBlock saved = userBlockRepository.saveAndFlush(new UserBlock(userId, targetUserId));

        // chat stale 캐시 제거 — 트랜잭션 안(커밋 전)에서 호출한다.
        // Redis 실패 시 RuntimeException 이 전파돼 블록 INSERT 까지 롤백된다(fail-closed):
        // 캐시를 못 지운 채로 차단을 확정해 stale 캐시가 차단을 누락시키는 상황을 막는다.
        blockCachePort.invalidateBlockCache(userId, targetUserId);

        return new UserBlockResponse(saved.getBlockId(), FriendPeerResponse.from(target), saved.getCreatedAt());
    }

    @Transactional
    public void unblockUser(String userId, String targetUserId) {
        UserBlock block = userBlockRepository.findByBlockerIdAndBlockedId(userId, targetUserId)
                .orElseThrow(() -> ApiException.badRequest("차단 상태가 아닙니다."));
        // fail-open: 캐시 먼저 무효화 후 삭제
        blockCachePort.invalidateBlockCache(userId, targetUserId);
        userBlockRepository.delete(block);
    }

    @Transactional(readOnly = true)
    public UserBlockListResponse getBlockedUsers(String userId, String cursor) {
        Pageable p = PageRequest.of(0, PAGE_SIZE, PAGE_SORT);
        List<UserBlock> items;
        if (cursor == null || cursor.isBlank()) {
            items = userBlockRepository.findBlocksFirstPage(userId, p);
        } else {
            Instant cursorAt = userBlockRepository.findCreatedAt(cursor).orElse(null);
            items = (cursorAt == null) ? List.of()
                    : userBlockRepository.findBlocksAfterCursor(userId, cursorAt, cursor, p);
        }

        List<UserBlockResponse> dtos = items.stream()
                .map(b -> new UserBlockResponse(
                        b.getBlockId(), FriendPeerResponse.from(b.getBlocked()), b.getCreatedAt()))
                .toList();
        String nextCursor = items.size() == PAGE_SIZE ? items.get(items.size() - 1).getBlockId() : null;
        return new UserBlockListResponse(dtos, nextCursor);
    }
}
