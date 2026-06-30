package site.krip.domain.tripmate.repository;

import org.jspecify.annotations.Nullable;
import site.krip.domain.tripmate.entity.TripmatePost;

import java.time.Instant;
import java.util.List;

/**
 * 여행 메이트 목록 키셋 페이지네이션 — 커서 유무 분기를 QueryDSL 동적 쿼리로 처리.
 */
public interface TripmatePostRepositoryCustom {

    /**
     * 표시(displayed) 글 한 페이지. {@code cursorAt}/{@code cursor} 가 모두 null 이면 첫 페이지,
     * 아니면 (createdAt, postId) 키셋 이후. 차단 관계(방향 무관) 작성자 글은 not exists 로 제외,
     * user·detail fetch join, createdAt desc·postId desc 정렬, 최대 {@code limit} 행.
     */
    List<TripmatePost> findDisplayed(String viewerId, @Nullable Instant cursorAt,
                                     @Nullable String cursor, int limit);
}
