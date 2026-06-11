package site.krip.domain.feed.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.krip.domain.auth.port.UserProfileView;
import site.krip.domain.auth.port.UserQueryPort;
import site.krip.domain.feed.dto.response.FeedPopupResponse;
import site.krip.domain.feed.dto.response.FeedPostResponse;
import site.krip.domain.feed.entity.FeedVisibility;
import site.krip.domain.feed.exception.PopupTargetNotFoundException;
import site.krip.domain.feed.repository.FeedPostRepository;

import java.util.List;

/**
 * 피드 팝업 — 프로필 정보 + 최근 9개 피드 합성.
 *
 * <p>user 미존재/회원가입 미완료 → 404 일원화(enumeration 차단), 차단 → 403. next_cursor 미제공.
 */
@Service
public class FeedPopupService {

    /** popup 그리드 (3×3). */
    private static final int POPUP_FEED_LIMIT = 9;

    private final UserQueryPort userQuery;
    private final FeedAccessService access;
    private final FeedPostRepository feedPostRepo;

    public FeedPopupService(UserQueryPort userQuery, FeedAccessService access,
                            FeedPostRepository feedPostRepo) {
        this.userQuery = userQuery;
        this.access = access;
        this.feedPostRepo = feedPostRepo;
    }

    @Transactional(readOnly = true)
    public FeedPopupResponse getPopup(String viewerId, String ownerId) {
        // 미존재/2차 가입 미완료(detail 없음) 모두 빈 결과 → 404 일원화(enumeration 차단).
        UserProfileView owner = userQuery.findProfile(ownerId)
                .orElseThrow(() -> new PopupTargetNotFoundException("존재하지 않는 유저입니다."));

        // viewer==owner fast-path 포함. 차단 시 404 일원화(존재하지 않는 게시물)
        List<FeedVisibility> visibilities = access.resolveViewerVisibilities(viewerId, ownerId);

        List<FeedPostResponse> feedItems = feedPostRepo
                .findByOwnerFirstPage(ownerId, visibilities, viewerId, PageRequest.of(0, POPUP_FEED_LIMIT))
                .stream()
                .map(FeedPostResponse::from)
                .toList();

        return new FeedPopupResponse(
                owner.userId(),
                owner.userName(),
                owner.nationality(),
                userQuery.findTravelStyles(ownerId),
                owner.profileImageUrl(),
                new FeedPopupResponse.PopupFeedSection(feedItems));
    }
}
