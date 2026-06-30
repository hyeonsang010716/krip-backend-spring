package site.krip.domain.friend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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
import site.krip.global.support.AfterCommit;
import site.krip.global.support.KeysetCursor;

import java.util.List;

/**
 * 유저 차단/해제/목록.
 *
 * <p>차단 시 두 유저 간 friendship(방향 무관)을 모두 정리한다. 차단 INSERT 는 uq_user_block_pair
 * 경합 가능 → 충돌 시 재조회. chat 차단 캐시 무효화는 포트로 위임.
 */
@Service
@RequiredArgsConstructor
public class UserBlockService {

    private static final int PAGE_SIZE = 30;

    private final UserBlockRepository userBlockRepository;
    private final FriendshipRepository friendshipRepository;
    private final UserRepository userRepository;
    private final BlockCachePort blockCachePort;
    private final TransactionTemplate txTemplate;

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
                .orElseThrow(() -> ApiException.notFound("존재하지 않는 유저입니다."));
        // 2차 미완료(detail==null) 유저는 유효한 소셜 대상이 아님 — 차단 응답/목록 매퍼 NPE 방지.
        if (target.getDetail() == null) {
            throw ApiException.badRequest("2차 회원가입이 완료되지 않은 유저입니다.");
        }

        if (userBlockRepository.existsByBlockerIdAndBlockedId(userId, targetUserId)) {
            throw ApiException.badRequest("이미 차단한 유저입니다.");
        }

        // 두 유저 간 friendship(방향 무관) 정리 — PENDING/ACCEPTED 즉시 단절, REJECTED 도 삭제.
        // 버전 무시 bulk delete: 동시 accept 가 버전을 올려도 차단이 항상 이긴다(엔티티 delete 면 낙관락 409 로 실패).
        friendshipRepository.deleteBetween(userId, targetUserId);

        UserBlock saved = userBlockRepository.saveAndFlush(new UserBlock(userId, targetUserId));

        // 캐시 무효화 — 커밋 전(fail-closed: Redis 실패 시 INSERT 까지 롤백)과 커밋 후(동시 read 가
        // 미커밋 INSERT 를 못 보고 "차단 아님" 으로 재적재한 stale 캐시 제거) 모두 같은 키를 DEL.
        invalidateBlockCacheBeforeAndAfterCommit(userId, targetUserId);

        return new UserBlockResponse(saved.getBlockId(), FriendPeerResponse.from(target), saved.getCreatedAt());
    }

    @Transactional
    public void unblockUser(String userId, String targetUserId) {
        UserBlock block = userBlockRepository.findByBlockerIdAndBlockedId(userId, targetUserId)
                .orElseThrow(() -> ApiException.notFound("차단 상태가 아닙니다."));
        userBlockRepository.delete(block);
        // 커밋 전(fail-closed)과 커밋 후(동시 read 가 미커밋 삭제를 못 보고 "차단됨" 으로 재적재한 stale 캐시
        // 제거) 이중 무효화. 잔여 race 는 캐시 TTL 로 상한(doBlock 과 동일).
        invalidateBlockCacheBeforeAndAfterCommit(userId, targetUserId);
    }

    /** 커밋 전·후 이중 무효화 — TOCTOU 재적재 창을 닫는다. 단건 무효화의 fail-closed 보장은 유지. */
    private void invalidateBlockCacheBeforeAndAfterCommit(String userId, String targetUserId) {
        blockCachePort.invalidateBlockCache(userId, targetUserId);
        AfterCommit.run(() -> blockCachePort.invalidateBlockCache(userId, targetUserId));
    }

    @Transactional(readOnly = true)
    public UserBlockListResponse getBlockedUsers(String userId, String cursor) {
        // 커서에 (createdAt, blockId)를 담아 디코딩 — 경계 행을 재조회하지 않아 그 행 삭제 시에도 안 잘린다.
        KeysetCursor.Decoded c = (cursor == null || cursor.isBlank()) ? null : KeysetCursor.decode(cursor);
        // PAGE_SIZE+1 fetch — 총 개수가 PAGE_SIZE 배수일 때 빈 다음 페이지를 가리키는 phantom 커서 방지.
        List<UserBlock> fetched = userBlockRepository.findBlocks(
                userId, c == null ? null : c.sortKey(), c == null ? null : c.id(), PAGE_SIZE + 1);
        boolean hasMore = fetched.size() > PAGE_SIZE;
        List<UserBlock> items = hasMore ? fetched.subList(0, PAGE_SIZE) : fetched;

        List<UserBlockResponse> dtos = items.stream()
                .map(b -> new UserBlockResponse(
                        b.getBlockId(), FriendPeerResponse.from(b.getBlocked()), b.getCreatedAt()))
                .toList();
        UserBlock last = hasMore ? items.get(items.size() - 1) : null;
        String nextCursor = last == null ? null : KeysetCursor.encode(last.getCreatedAt(), last.getBlockId());
        return new UserBlockListResponse(dtos, nextCursor);
    }
}
