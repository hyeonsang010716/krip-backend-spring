package site.krip.domain.friend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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
import site.krip.global.support.KeysetCursor;

import java.time.Instant;
import java.util.List;

/**
 * 친구 요청/수락/거절/취소/삭제 + 목록.
 *
 * <p>친구 요청 INSERT 는 canonical unique index 경합을 만날 수 있어, 충돌 시 새 트랜잭션에서
 * 재조회해 정확한 메시지를 돌려준다.
 */
@Service
@RequiredArgsConstructor
public class FriendshipService {

    private static final int PAGE_SIZE = 30;
    // hasMore 판정용 +1 fetch — 총 개수가 PAGE_SIZE 배수일 때 빈 다음 페이지를 가리키는 phantom 커서 방지.
    private static final int FETCH_SIZE = PAGE_SIZE + 1;

    private final FriendshipRepository friendshipRepository;
    private final UserBlockRepository userBlockRepository;
    private final UserRepository userRepository;
    private final TransactionTemplate txTemplate;

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
                .orElseThrow(() -> ApiException.notFound("존재하지 않는 유저입니다."));
        // 2차 미완료(detail==null) 유저는 유효한 소셜 대상이 아님 — 친구 관계에 끼면 목록 매퍼 NPE.
        if (addressee.getDetail() == null) {
            throw ApiException.badRequest("2차 회원가입이 완료되지 않은 유저입니다.");
        }

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
            // REJECTED → 재요청: 같은 행을 PENDING 으로 되살린다(friendshipId 유지 → stale-id 404 방지).
            // 동시 재요청은 @Version 으로 한쪽만 성공(409). created_at 은 유지, 목록 정렬은 updated_at 기준.
            existing.reopenAsPending(requesterId, addresseeId);
            Friendship saved = friendshipRepository.saveAndFlush(existing);
            return toResponse(saved, requesterId, addressee);
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
                .orElseThrow(() -> ApiException.notFound(notFoundMessage));
    }

    // ──────────────────── 목록 조회 ────────────────────

    @Transactional(readOnly = true)
    public FriendshipListResponse getFriends(String userId, String cursor) {
        List<Friendship> items = (cursor == null || cursor.isBlank())
                ? friendshipRepository.findFriends(userId, FriendshipStatus.ACCEPTED, null, null, FETCH_SIZE)
                : afterCursor(cursor, (cAt, cId) -> friendshipRepository.findFriends(
                        userId, FriendshipStatus.ACCEPTED, cAt, cId, FETCH_SIZE));
        return buildListResponse(items, userId);
    }

    @Transactional(readOnly = true)
    public FriendshipListResponse getReceivedRequests(String userId, String cursor) {
        List<Friendship> items = (cursor == null || cursor.isBlank())
                ? friendshipRepository.findReceived(userId, FriendshipStatus.PENDING, null, null, FETCH_SIZE)
                : afterCursor(cursor, (cAt, cId) -> friendshipRepository.findReceived(
                        userId, FriendshipStatus.PENDING, cAt, cId, FETCH_SIZE));
        return buildListResponse(items, userId);
    }

    @Transactional(readOnly = true)
    public FriendshipListResponse getSentRequests(String userId, String cursor) {
        List<Friendship> items = (cursor == null || cursor.isBlank())
                ? friendshipRepository.findSent(userId, FriendshipStatus.PENDING, null, null, FETCH_SIZE)
                : afterCursor(cursor, (cAt, cId) -> friendshipRepository.findSent(
                        userId, FriendshipStatus.PENDING, cAt, cId, FETCH_SIZE));
        return buildListResponse(items, userId);
    }

    /** 커서에 (정렬키, id)를 담아 디코딩 — 경계 행을 재조회하지 않으므로 그 행이 삭제돼도 목록이 안 잘린다. */
    private List<Friendship> afterCursor(String cursor,
            java.util.function.BiFunction<Instant, String, List<Friendship>> query) {
        KeysetCursor.Decoded c = KeysetCursor.decode(cursor);
        return query.apply(c.sortKey(), c.id());
    }

    // ──────────────────── 내부 변환 ────────────────────

    private FriendshipListResponse buildListResponse(List<Friendship> fetched, String viewerId) {
        boolean hasMore = fetched.size() > PAGE_SIZE;
        List<Friendship> items = hasMore ? fetched.subList(0, PAGE_SIZE) : fetched;
        List<FriendshipResponse> dtos = items.stream()
                .map(f -> toResponse(f, viewerId, peerOf(f, viewerId)))
                .toList();
        Friendship last = hasMore ? items.get(items.size() - 1) : null;
        String nextCursor = last == null ? null : KeysetCursor.encode(last.getUpdatedAt(), last.getFriendshipId());
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
