package site.krip.domain.feed.repository;

import org.jspecify.annotations.Nullable;
import site.krip.domain.feed.entity.FeedPostComment;

import java.time.Instant;
import java.util.List;

/**
 * 댓글 목록 키셋 페이지네이션 — 커서 유무 분기를 QueryDSL 동적 쿼리로 처리.
 */
public interface FeedPostCommentRepositoryCustom {

    /**
     * 게시글 댓글 한 페이지. {@code cursorAt}/{@code cursor} 가 모두 null 이면 첫 페이지,
     * 아니면 (createdAt, commentId) 키셋 이후. createdAt desc·commentId desc 정렬,
     * 최대 {@code limit} 행.
     */
    List<FeedPostComment> findByPost(String postId, @Nullable Instant cursorAt,
                                     @Nullable String cursor, int limit);
}
