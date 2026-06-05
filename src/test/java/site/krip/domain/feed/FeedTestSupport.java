package site.krip.domain.feed;

import org.springframework.beans.factory.annotation.Autowired;
import site.krip.domain.feed.entity.FeedPost;
import site.krip.domain.feed.entity.FeedVisibility;
import site.krip.domain.feed.repository.FeedPostRepository;
import site.krip.domain.friend.entity.Friendship;
import site.krip.domain.friend.entity.UserBlock;
import site.krip.domain.friend.repository.FriendshipRepository;
import site.krip.domain.friend.repository.UserBlockRepository;
import site.krip.global.support.IdGenerator;
import site.krip.support.IntegrationTestSupport;

/**
 * 피드 E2E 공통 베이스 — S3 가 없는 환경이라 업로드 플로우를 못 타므로, 가시성/좋아요/댓글/삭제 검증에
 * 필요한 {@link FeedPost} 행을 리포지토리로 직접 시드한다. URL 3종은 NOT NULL 이므로 더미 값을 채운다.
 *
 * <p>친구/차단 관계도 리포지토리로 직접 시드(API 우회) — ACCEPTED 친구는 {@code new Friendship().accept()},
 * 단방향 차단은 {@code new UserBlock(blocker, blocked)}.
 */
abstract class FeedTestSupport extends IntegrationTestSupport {

    @Autowired
    protected FeedPostRepository feedPostRepository;

    @Autowired
    protected FriendshipRepository friendshipRepository;

    @Autowired
    protected UserBlockRepository userBlockRepository;

    /** 지정 visibility 의 피드 게시물을 직접 INSERT 하고 post_id 반환. caption 은 null 가능. */
    protected String seedPost(String ownerId, FeedVisibility visibility, String caption) {
        String postId = IdGenerator.feedPostId();
        FeedPost post = new FeedPost(
                postId, ownerId, visibility, caption,
                "https://example.com/feed/" + postId + "/original.jpg",
                "https://example.com/feed/" + postId + "/small.jpg",
                "https://example.com/feed/" + postId + "/medium.jpg");
        // assigned-id → save() 는 merge 로 동작하나 반환 인스턴스만 사용하므로 안전.
        feedPostRepository.save(post);
        return postId;
    }

    /** 두 유저를 ACCEPTED 친구로 만든다(방향 무관). */
    protected void makeFriends(String a, String b) {
        Friendship friendship = new Friendship(a, b);
        friendship.accept();
        friendshipRepository.save(friendship);
    }

    /** blocker 가 blocked 를 단방향 차단(가시성 helper 는 방향 무관으로 본다). */
    protected void block(String blocker, String blocked) {
        userBlockRepository.save(new UserBlock(blocker, blocked));
    }
}
