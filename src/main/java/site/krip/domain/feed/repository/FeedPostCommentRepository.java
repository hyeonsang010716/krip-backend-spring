package site.krip.domain.feed.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import site.krip.domain.feed.entity.FeedPostComment;

/**
 * FeedPostComment RDB 접근. 커서 페이지네이션은 {@link FeedPostCommentRepositoryCustom} 참고.
 */
public interface FeedPostCommentRepository extends JpaRepository<FeedPostComment, String>, FeedPostCommentRepositoryCustom {

    /** 모바일 한 화면 fit. */
    int PAGE_SIZE = 20;
}
