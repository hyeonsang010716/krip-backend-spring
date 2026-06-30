package site.krip.domain.tripmate.repository;

import org.jspecify.annotations.Nullable;
import site.krip.domain.tripmate.entity.TripmatePostLike;

import java.time.Instant;
import java.util.List;

/**
 * 게시글 좋아요 목록 키셋 페이지네이션 — 커서 유무 분기를 QueryDSL 동적 쿼리로 처리.
 */
public interface TripmatePostLikeRepositoryCustom {

    /**
     * 좋아요 한 페이지. {@code cursorAt}/{@code cursorUserId} 가 모두 null 이면 첫 페이지,
     * 아니면 (createdAt, userId) 키셋 이후. createdAt desc·userId desc 정렬, 최대 {@code limit} 행.
     */
    List<TripmatePostLike> findLikes(String postId, @Nullable Instant cursorAt,
                                     @Nullable String cursorUserId, int limit);
}
