package site.krip.domain.friend.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.krip.domain.auth.entity.TravelStyle;
import site.krip.domain.auth.entity.User;
import site.krip.domain.auth.entity.UserStatus;
import site.krip.domain.auth.entity.UserTravelStyle;
import site.krip.domain.auth.repository.UserTravelStyleRepository;
import site.krip.domain.friend.dto.response.FriendSearchItemResponse;
import site.krip.domain.friend.dto.response.FriendSearchListResponse;
import site.krip.domain.friend.entity.Friendship;
import site.krip.domain.friend.entity.FriendshipStatus;
import site.krip.domain.friend.repository.FriendUserSearchRepository;
import site.krip.domain.friend.repository.FriendshipRepository;
import site.krip.global.common.exception.ApiException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 친구 추가 화면 유저 검색.
 *
 * <p>검색 결과에 viewer 기준 친구 관계 상태를 매핑한다(N+1 회피 일괄 조회). 여행 스타일은
 * LIMIT 충돌을 피해 별도 IN 쿼리로 배치 로드. 차단 유저는 검색 단계에서 이미 제외됨.
 */
@Service
public class FriendSearchService {

    private static final int PAGE_SIZE = 30;
    private static final Sort PAGE_SORT =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("userId"));

    private final FriendUserSearchRepository searchRepository;
    private final FriendshipRepository friendshipRepository;
    private final UserTravelStyleRepository travelStyleRepository;

    public FriendSearchService(FriendUserSearchRepository searchRepository,
                               FriendshipRepository friendshipRepository,
                               UserTravelStyleRepository travelStyleRepository) {
        this.searchRepository = searchRepository;
        this.friendshipRepository = friendshipRepository;
        this.travelStyleRepository = travelStyleRepository;
    }

    @Transactional(readOnly = true)
    public FriendSearchListResponse search(String viewerId, String keyword, String cursor) {
        String normalized = keyword == null ? "" : keyword.strip();
        if (normalized.isEmpty()) {
            throw new ApiException(400, "검색어를 입력해주세요.");
        }
        String pattern = escapeLike(normalized);
        Pageable p = PageRequest.of(0, PAGE_SIZE, PAGE_SORT);

        List<User> users;
        if (cursor == null || cursor.isBlank()) {
            users = searchRepository.searchFirstPage(viewerId, UserStatus.ACTIVE, pattern, p);
        } else {
            Instant cursorAt = searchRepository.findCreatedAt(cursor).orElse(null);
            users = (cursorAt == null) ? List.of()
                    : searchRepository.searchAfterCursor(viewerId, UserStatus.ACTIVE, pattern, cursorAt, cursor, p);
        }

        if (users.isEmpty()) {
            return new FriendSearchListResponse(List.of(), null);
        }

        List<String> peerIds = users.stream().map(User::getUserId).toList();

        // peer 별 friendship (방향 무관) 일괄 조회
        Map<String, Friendship> friendshipByPeer = new HashMap<>();
        for (Friendship f : friendshipRepository.findFriendshipsWith(viewerId, peerIds)) {
            String peerId = f.getRequesterId().equals(viewerId) ? f.getAddresseeId() : f.getRequesterId();
            friendshipByPeer.put(peerId, f);
        }

        // 여행 스타일 배치 로드
        Map<String, List<TravelStyle>> stylesByUser = new HashMap<>();
        for (UserTravelStyle s : travelStyleRepository.findByUserIds(peerIds)) {
            stylesByUser.computeIfAbsent(s.getUser().getUserId(), k -> new java.util.ArrayList<>()).add(s.getStyle());
        }

        List<FriendSearchItemResponse> items = users.stream()
                .map(u -> toItem(viewerId, u, friendshipByPeer.get(u.getUserId()),
                        stylesByUser.getOrDefault(u.getUserId(), List.of())))
                .toList();
        String nextCursor = users.size() == PAGE_SIZE ? users.get(users.size() - 1).getUserId() : null;
        return new FriendSearchListResponse(items, nextCursor);
    }

    private FriendSearchItemResponse toItem(String viewerId, User user, Friendship friendship,
                                            List<TravelStyle> styles) {
        FriendshipStatus status = null;
        Boolean isRequester = null;
        if (friendship != null) {
            status = friendship.getStatus();
            // isRequester 는 PENDING 일 때만 의미 있음
            isRequester = friendship.getStatus() == FriendshipStatus.PENDING
                    ? friendship.getRequesterId().equals(viewerId)
                    : null;
        }
        var d = user.getDetail();
        return new FriendSearchItemResponse(
                user.getUserId(), d.getUserName(), d.getProfileImageUrl(), d.getNationality(),
                styles, status, isRequester, false);
    }

    /** SQL LIKE 메타문자 이스케이프 (escape '!'). */
    private String escapeLike(String keyword) {
        String escaped = keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }
}
