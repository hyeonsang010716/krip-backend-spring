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
import site.krip.domain.friend.dto.response.FriendshipListResponse;
import site.krip.domain.friend.dto.response.FriendshipResponse;
import site.krip.domain.friend.entity.Friendship;
import site.krip.domain.friend.entity.FriendshipStatus;
import site.krip.domain.friend.entity.UserBlock;
import site.krip.domain.friend.exception.FriendForbiddenException;
import site.krip.domain.friend.repository.FriendshipRepository;
import site.krip.domain.friend.repository.UserBlockRepository;
import site.krip.global.common.exception.ApiException;

import java.time.Instant;
import java.util.List;

/**
 * 친구 요청/수락/거절/취소/삭제 + 목록.
 *
 * <p>친구 요청 INSERT 는 canonical unique index 경합을 만날 수 있어, 충돌 시 새 트랜잭션에서
 * 재조회해 정확한 메시지를 돌려준다.
 */
@Service
public class FriendshipService {

    private static final int PAGE_SIZE = 30;
    private static final Sort PAGE_SORT =
            Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("friendshipId"));

    private final FriendshipRepository friendshipRepository;
    private final UserBlockRepository userBlockRepository;
    private final UserRepository userRepository;
    private final TransactionTemplate txTemplate;

    public FriendshipService(FriendshipRepository friendshipRepository,
                             UserBlockRepository userBlockRepository,
                             UserRepository userRepository,
                             TransactionTemplate txTemplate) {
        this.friendshipRepository = friendshipRepository;
        this.userBlockRepository = userBlockRepository;
        this.userRepository = userRepository;
        this.txTemplate = txTemplate;
    }

    // ──────────────────── 친구 요청 ────────────────────

    public FriendshipResponse sendRequest(String requesterId, String addresseeId) {
        if (requesterId.equals(addresseeId)) {
            throw ApiException.badRequest("자기 자신에게 친구 요청을 보낼 수 없습니다.");
        }
        try {
            return txTemplate.execute(s -> doSendRequest(requesterId, addresseeId));
        } catch (DataIntegrityViolationException e) {
            // canonical unique 경합 — 새 트랜잭션에서 재조회 후 정확한 메시지로 변환(항상 throw).
            return txTemplate.execute(s -> {
                Friendship existing = friendshipRepository.findBetween(requesterId, addresseeId)
                        .orElseThrow(() -> ApiException.badRequest("친구 요청을 처리하지 못했습니다. 잠시 후 다시 시도해주세요."));
                raiseExistingError(existing, requesterId);
                throw ApiException.badRequest("친구 요청 상태가 변경되었습니다. 다시 시도해주세요.");
            });
        }
    }

    private FriendshipResponse doSendRequest(String requesterId, String addresseeId) {
        User addressee = userRepository.findByIdWithProfile(addresseeId)
                .orElseThrow(() -> ApiException.badRequest("존재하지 않는 유저입니다."));

        // 차단 관계 우선 검증 — 내가 건 차단 우선 안내(actionable)
        List<UserBlock> blocks = userBlockRepository.findBlocksBetween(requesterId, addresseeId);
        if (blocks.stream().anyMatch(b -> b.getBlockerId().equals(requesterId))) {
            throw ApiException.badRequest("차단한 유저입니다. 먼저 차단을 해제해주세요.");
        }
        if (!blocks.isEmpty()) {
            throw ApiException.badRequest("해당 유저에게 친구 요청을 보낼 수 없습니다.");
        }

        Friendship existing = friendshipRepository.findBetween(requesterId, addresseeId).orElse(null);
        if (existing != null) {
            if (existing.getStatus() != FriendshipStatus.REJECTED) {
                raiseExistingError(existing, requesterId);
            }
            // REJECTED → 재요청 허용 (방향 반전 포함 upsert)
            existing.reopenAsPending(requesterId, addresseeId);
            friendshipRepository.flush(); // updatedAt 갱신
            return toResponse(existing, requesterId, addressee);
        }

        Friendship saved = friendshipRepository.saveAndFlush(new Friendship(requesterId, addresseeId));
        return toResponse(saved, requesterId, addressee);
    }

    /** 기존 관계 상태에 따른 400 에러 발생 (PENDING/ACCEPTED). REJECTED 는 호출측에서 별도 처리. */
    private void raiseExistingError(Friendship existing, String requesterId) {
        if (existing.getStatus() == FriendshipStatus.PENDING) {
            if (existing.getRequesterId().equals(requesterId)) {
                throw ApiException.badRequest("이미 친구 요청을 보낸 상대입니다.");
            }
            throw ApiException.badRequest("이미 친구 요청이 와 있는 상대입니다. 받은 요청에서 수락해주세요.");
        }
        if (existing.getStatus() == FriendshipStatus.ACCEPTED) {
            throw ApiException.badRequest("이미 친구 관계입니다.");
        }
    }

    // ──────────────────── 수락 / 거절 / 취소 / 삭제 ────────────────────

    @Transactional
    public void acceptRequest(String friendshipId, String userId) {
        Friendship f = requireFriendship(friendshipId, "존재하지 않는 친구 요청입니다.");
        if (!f.getAddresseeId().equals(userId)) {
            throw new FriendForbiddenException("요청 수락 권한이 없습니다.");
        }
        if (f.getStatus() != FriendshipStatus.PENDING) {
            throw ApiException.badRequest("대기 중인 요청만 수락할 수 있습니다.");
        }
        // 차단-친구요청 TOCTOU 방어: 경합으로 PENDING 이 남았어도, 수락(차단 우회의 유일 경로)에서 차단 재검사.
        if (!userBlockRepository.findBlocksBetween(f.getAddresseeId(), f.getRequesterId()).isEmpty()) {
            throw ApiException.badRequest("차단 관계인 유저의 친구 요청은 수락할 수 없습니다.");
        }
        f.accept();
    }

    @Transactional
    public void rejectRequest(String friendshipId, String userId) {
        Friendship f = requireFriendship(friendshipId, "존재하지 않는 친구 요청입니다.");
        if (!f.getAddresseeId().equals(userId)) {
            throw new FriendForbiddenException("요청 거절 권한이 없습니다.");
        }
        if (f.getStatus() != FriendshipStatus.PENDING) {
            throw ApiException.badRequest("대기 중인 요청만 거절할 수 있습니다.");
        }
        f.reject();
    }

    @Transactional
    public void cancelRequest(String friendshipId, String userId) {
        Friendship f = requireFriendship(friendshipId, "존재하지 않는 친구 요청입니다.");
        if (!f.getRequesterId().equals(userId)) {
            throw new FriendForbiddenException("요청 취소 권한이 없습니다.");
        }
        if (f.getStatus() != FriendshipStatus.PENDING) {
            throw ApiException.badRequest("대기 중인 요청만 취소할 수 있습니다.");
        }
        friendshipRepository.delete(f);
    }

    @Transactional
    public void removeFriend(String friendshipId, String userId) {
        Friendship f = requireFriendship(friendshipId, "존재하지 않는 친구 관계입니다.");
        if (!userId.equals(f.getRequesterId()) && !userId.equals(f.getAddresseeId())) {
            throw new FriendForbiddenException("친구 삭제 권한이 없습니다.");
        }
        if (f.getStatus() != FriendshipStatus.ACCEPTED) {
            throw ApiException.badRequest("친구 상태에서만 삭제할 수 있습니다.");
        }
        friendshipRepository.delete(f);
    }

    private Friendship requireFriendship(String friendshipId, String notFoundMessage) {
        return friendshipRepository.findById(friendshipId)
                .orElseThrow(() -> ApiException.badRequest(notFoundMessage));
    }

    // ──────────────────── 목록 조회 ────────────────────

    @Transactional(readOnly = true)
    public FriendshipListResponse getFriends(String userId, String cursor) {
        Pageable p = PageRequest.of(0, PAGE_SIZE, PAGE_SORT);
        List<Friendship> items = (cursor == null || cursor.isBlank())
                ? friendshipRepository.findFriendsFirstPage(userId, FriendshipStatus.ACCEPTED, p)
                : afterCursor(cursor, cAt -> friendshipRepository.findFriendsAfterCursor(
                        userId, FriendshipStatus.ACCEPTED, cAt, cursor, p));
        return buildListResponse(items, userId);
    }

    @Transactional(readOnly = true)
    public FriendshipListResponse getReceivedRequests(String userId, String cursor) {
        Pageable p = PageRequest.of(0, PAGE_SIZE, PAGE_SORT);
        List<Friendship> items = (cursor == null || cursor.isBlank())
                ? friendshipRepository.findReceivedFirstPage(userId, FriendshipStatus.PENDING, p)
                : afterCursor(cursor, cAt -> friendshipRepository.findReceivedAfterCursor(
                        userId, FriendshipStatus.PENDING, cAt, cursor, p));
        return buildListResponse(items, userId);
    }

    @Transactional(readOnly = true)
    public FriendshipListResponse getSentRequests(String userId, String cursor) {
        Pageable p = PageRequest.of(0, PAGE_SIZE, PAGE_SORT);
        List<Friendship> items = (cursor == null || cursor.isBlank())
                ? friendshipRepository.findSentFirstPage(userId, FriendshipStatus.PENDING, p)
                : afterCursor(cursor, cAt -> friendshipRepository.findSentAfterCursor(
                        userId, FriendshipStatus.PENDING, cAt, cursor, p));
        return buildListResponse(items, userId);
    }

    private List<Friendship> afterCursor(String cursor, java.util.function.Function<Instant, List<Friendship>> query) {
        Instant cursorAt = friendshipRepository.findUpdatedAt(cursor).orElse(null);
        return cursorAt == null ? List.of() : query.apply(cursorAt);
    }

    // ──────────────────── 내부 변환 ────────────────────

    private FriendshipListResponse buildListResponse(List<Friendship> items, String viewerId) {
        List<FriendshipResponse> dtos = items.stream()
                .map(f -> toResponse(f, viewerId, peerOf(f, viewerId)))
                .toList();
        String nextCursor = items.size() == PAGE_SIZE ? items.get(items.size() - 1).getFriendshipId() : null;
        return new FriendshipListResponse(dtos, nextCursor);
    }

    private User peerOf(Friendship f, String viewerId) {
        return f.getRequesterId().equals(viewerId) ? f.getAddressee() : f.getRequester();
    }

    private FriendshipResponse toResponse(Friendship f, String viewerId, User peer) {
        return new FriendshipResponse(
                f.getFriendshipId(),
                f.getStatus(),
                FriendPeerResponse.from(peer),
                f.getRequesterId().equals(viewerId),
                f.getCreatedAt(),
                f.getUpdatedAt());
    }
}
