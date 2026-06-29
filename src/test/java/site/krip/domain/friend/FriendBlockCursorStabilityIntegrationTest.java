package site.krip.domain.friend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import site.krip.domain.friend.dto.response.FriendshipListResponse;
import site.krip.domain.friend.dto.response.FriendshipResponse;
import site.krip.domain.friend.dto.response.UserBlockListResponse;
import site.krip.domain.friend.dto.response.UserBlockResponse;
import site.krip.domain.friend.entity.Friendship;
import site.krip.domain.friend.entity.FriendshipStatus;
import site.krip.domain.friend.entity.UserBlock;
import site.krip.domain.friend.service.FriendshipService;
import site.krip.domain.friend.service.UserBlockService;
import site.krip.global.support.KeysetCursor;
import site.krip.support.IntegrationTestSupport;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 친구/차단 목록 커서 안정성 — 경계 행이 동시 삭제돼도 다음 페이지가 안 잘리는지 검증(회귀).
 * 구버그: 커서가 id만 담아 행 소실 시 List.of() 반환 → 클라가 "끝"으로 오인. (정렬키, id) 인코딩으로 수정.
 */
class FriendBlockCursorStabilityIntegrationTest extends IntegrationTestSupport {

    /** 서비스 첫 페이지와 동일한 크기 — 2행을 한 페이지에 담아 경계/다음 행을 가린다. */
    private static final int FIRST_PAGE_SIZE = 30;

    @Autowired
    private FriendshipService friendshipService;
    @Autowired
    private UserBlockService userBlockService;

    @Test
    @DisplayName("친구 목록 — 경계 친구가 나를 끊어 행이 삭제돼도 다음 페이지가 안 잘린다")
    void friendListNotTruncatedWhenBoundaryDeleted() {
        String me = fixtures.createActiveUser("나");
        String f1 = fixtures.createActiveUser("친구1");
        String f2 = fixtures.createActiveUser("친구2");
        makeFriends(me, f1);
        makeFriends(me, f2);

        // 서비스와 동일 정렬로 정렬해 1페이지 경계(첫 행)/그 다음 행을 정한다.
        List<Friendship> sorted = friendshipRepository.findFriendsFirstPage(me, FriendshipStatus.ACCEPTED,
                PageRequest.of(0, FIRST_PAGE_SIZE, Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("friendshipId"))));
        assertThat(sorted).hasSize(2);
        Friendship boundary = sorted.get(0);
        Friendship next = sorted.get(1);
        String cursor = KeysetCursor.encode(boundary.getUpdatedAt(), boundary.getFriendshipId());

        // 경계 친구가 나를 끊음 → 경계 행 삭제.
        friendshipRepository.delete(boundary);
        friendshipRepository.flush();

        FriendshipListResponse page2 = friendshipService.getFriends(me, cursor);

        assertThat(page2.items())
                .isNotEmpty()
                .extracting(FriendshipResponse::friendshipId)
                .contains(next.getFriendshipId());
    }

    @Test
    @DisplayName("차단 목록 — 경계 차단 행이 삭제(해제)돼도 다음 페이지가 안 잘린다")
    void blockListNotTruncatedWhenBoundaryDeleted() {
        String me = fixtures.createActiveUser("나");
        String t1 = fixtures.createActiveUser("차단1");
        String t2 = fixtures.createActiveUser("차단2");
        block(me, t1);
        block(me, t2);
        userBlockRepository.flush();

        List<UserBlock> sorted = userBlockRepository.findBlocksFirstPage(me,
                PageRequest.of(0, FIRST_PAGE_SIZE, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("blockId"))));
        assertThat(sorted).hasSize(2);
        UserBlock boundary = sorted.get(0);
        UserBlock next = sorted.get(1);
        String cursor = KeysetCursor.encode(boundary.getCreatedAt(), boundary.getBlockId());

        userBlockRepository.delete(boundary);
        userBlockRepository.flush();

        UserBlockListResponse page2 = userBlockService.getBlockedUsers(me, cursor);

        assertThat(page2.items())
                .isNotEmpty()
                .extracting(UserBlockResponse::blockId)
                .contains(next.getBlockId());
    }
}
