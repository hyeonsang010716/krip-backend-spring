package site.krip.domain.feed.service;

import org.springframework.stereotype.Component;
import site.krip.domain.feed.entity.FeedPost;
import site.krip.domain.feed.entity.FeedVisibility;
import site.krip.domain.feed.exception.FeedBlockedException;
import site.krip.domain.feed.exception.FeedNotFoundException;
import site.krip.domain.feed.repository.FeedPostRepository;
import site.krip.domain.feed.repository.FeedPostRow;
import site.krip.domain.friend.entity.Friendship;
import site.krip.domain.friend.entity.FriendshipStatus;
import site.krip.domain.friend.repository.FriendshipRepository;
import site.krip.domain.friend.repository.UserBlockRepository;

import java.util.List;

/**
 * 피드 접근 권한 service 간 공유 helper.
 *
 * <p>block 우선 → friendship(ACCEPTED) → visibility. 미충족은 404 일원화(정보 누출 회피),
 * 양방향 차단은 403. viewer==owner fast-path 는 차단/친구 조회 skip.
 */
@Component
public class FeedAccessService {

    private final UserBlockRepository blockRepo;
    private final FriendshipRepository friendshipRepo;
    private final FeedPostRepository feedPostRepo;

    public FeedAccessService(UserBlockRepository blockRepo, FriendshipRepository friendshipRepo,
                             FeedPostRepository feedPostRepo) {
        this.blockRepo = blockRepo;
        this.friendshipRepo = friendshipRepo;
        this.feedPostRepo = feedPostRepo;
    }

    /** viewer 가 owner 피드에서 볼 수 있는 visibility 부분집합. 양방향 차단 시 {@link FeedBlockedException}. */
    public List<FeedVisibility> resolveViewerVisibilities(String viewerId, String ownerId) {
        if (viewerId.equals(ownerId)) {
            return List.of(FeedVisibility.values());
        }
        if (!blockRepo.findBlocksBetween(viewerId, ownerId).isEmpty()) {
            throw new FeedBlockedException("차단 관계인 유저의 피드에 접근할 수 없습니다.");
        }
        Friendship friendship = friendshipRepo.findBetween(viewerId, ownerId).orElse(null);
        boolean isFriend = friendship != null && friendship.getStatus() == FriendshipStatus.ACCEPTED;

        // 비-owner·비차단: PUBLIC 항상, FRIENDS 는 친구만, PRIVATE 제외
        if (isFriend) {
            return List.of(FeedVisibility.FRIENDS, FeedVisibility.PUBLIC);
        }
        return List.of(FeedVisibility.PUBLIC);
    }

    /** 단건 로드 + viewer 가시성 검증. 미존재→404, 차단→403, visibility 미충족→404. */
    public FeedPostRow loadViewablePost(String viewerId, String postId) {
        List<Object[]> rows = feedPostRepo.findRowByPostId(postId, viewerId);
        if (rows.isEmpty()) {
            throw new FeedNotFoundException("존재하지 않는 게시물입니다.");
        }
        FeedPostRow row = FeedPostRow.fromTuple(rows.get(0));
        FeedPost post = row.post();
        if (post.getUserId().equals(viewerId)) {
            return row;
        }
        List<FeedVisibility> visibilities = resolveViewerVisibilities(viewerId, post.getUserId());
        if (!visibilities.contains(post.getVisibility())) {
            throw new FeedNotFoundException("존재하지 않는 게시물입니다.");
        }
        return row;
    }
}
