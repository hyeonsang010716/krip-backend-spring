package site.krip.domain.friend.repository;

import org.jspecify.annotations.Nullable;
import site.krip.domain.friend.entity.UserBlock;

import java.time.Instant;
import java.util.List;

/**
 * 차단 목록 키셋 페이지네이션 — 커서 유무 분기를 QueryDSL 동적 쿼리로 처리.
 */
public interface UserBlockRepositoryCustom {

    /**
     * 차단 목록 한 페이지. {@code cursorAt}/{@code cursor} 가 모두 null 이면 첫 페이지,
     * 아니면 (createdAt, blockId) 키셋 이후. blocked 프로필+detail fetch join,
     * createdAt desc·blockId desc 정렬, 최대 {@code limit} 행.
     */
    List<UserBlock> findBlocks(String blockerId, @Nullable Instant cursorAt,
                               @Nullable String cursor, int limit);
}
