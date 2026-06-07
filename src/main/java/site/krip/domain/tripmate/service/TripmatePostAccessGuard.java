package site.krip.domain.tripmate.service;

import org.springframework.stereotype.Component;
import site.krip.domain.friend.repository.UserBlockRepository;
import site.krip.domain.tripmate.entity.TripmatePost;
import site.krip.domain.tripmate.exception.PostNotFoundException;
import site.krip.domain.tripmate.repository.TripmatePostRepository;

/**
 * 게시글 가시성 게이트 — 단건 조회/좋아요가 공유한다.
 *
 * <p>비-작성자에게 숨김(displayed=false)·차단 관계 글은 미노출. 존재 은닉을 위해 미존재·숨김·차단을 모두 404 로 일원화한다.
 */
@Component
public class TripmatePostAccessGuard {

    private final TripmatePostRepository postRepository;
    private final UserBlockRepository blockRepository;

    public TripmatePostAccessGuard(TripmatePostRepository postRepository, UserBlockRepository blockRepository) {
        this.postRepository = postRepository;
        this.blockRepository = blockRepository;
    }

    /** 조회 가능한 게시글을 로드하거나 404. */
    public TripmatePost loadViewablePost(String viewerId, String postId) {
        TripmatePost post = postRepository.findById(postId).orElseThrow(PostNotFoundException::new);
        verifyViewable(post, viewerId);
        return post;
    }

    /** 이미 로드한 게시글의 가시성 검증 — 비-작성자에게 숨김/차단이면 404. */
    public void verifyViewable(TripmatePost post, String viewerId) {
        if (post.getUserId().equals(viewerId)) {
            return;
        }
        if (!post.isDisplayed()) {
            throw new PostNotFoundException();
        }
        if (viewerId != null && !blockRepository.findBlocksBetween(viewerId, post.getUserId()).isEmpty()) {
            throw new PostNotFoundException();
        }
    }
}
