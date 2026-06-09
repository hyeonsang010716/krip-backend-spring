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
import site.krip.domain.friend.repository.FriendshipRepository;
import site.krip.domain.friend.repository.UserBlockRepository;
import site.krip.domain.friend.service.FriendshipService;
import site.krip.domain.friend.service.UserBlockService;
import site.krip.global.support.KeysetCursor;
import site.krip.support.IntegrationTestSupport;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 친구/차단 목록 커서 안정성 — 페이지 경계 행이 동시에 삭제돼도 다음 페이지가 잘리지 않는지 검증(회귀).
 *
 * <p>구버그: 커서가 id 만 담아 다음 페이지에서 그 행의 정렬키를 재조회 → 경계 친구가 나를 끊어 행이 사라지면
 * {@code List.of()} 반환 → 클라가 "끝"으로 오인하고 잔여 목록을 통째로 잃었다. 커서에 (정렬키, id)를 인코딩해 수정.
 */
class FriendBlockCursorStabilityIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private FriendshipService friendshipService;
    @Autowired
    private FriendshipRepository friendshipRepository;
    @Autowired
    private UserBlockService userBlockService;
    @Autowired
    private UserBlockRepository userBlockRepository;

    @Test
    @DisplayName("친구 목록 — 경계 친구가 나를 끊어 행이 삭제돼도 다음 페이지가 안 잘린다")
    void friendListNotTruncatedWhenBoundaryDeleted() {
        String me = fixtures.createActiveUser("나");
        String f1 = fixtures.createActiveUser("친구1");
        String f2 = fixtures.createActiveUser("친구2");
        saveAccepted(me, f1);
        saveAccepted(me, f2);

        // 서비스와 동일 정렬로 정렬해 1페이지 경계(첫 행)/그 다음 행을 정한다.
        List<Friendship> sorted = friendshipRepository.findFriendsFirstPage(me, FriendshipStatus.ACCEPTED,
                PageRequest.of(0, 30, Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("friendshipId"))));
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
        userBlockRepository.saveAndFlush(new UserBlock(me, t1));
        userBlockRepository.saveAndFlush(new UserBlock(me, t2));

        List<UserBlock> sorted = userBlockRepository.findBlocksFirstPage(me,
                PageRequest.of(0, 30, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("blockId"))));
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

    private void saveAccepted(String requesterId, String addresseeId) {
        Friendship f = new Friendship(requesterId, addresseeId);
        f.accept();
        friendshipRepository.saveAndFlush(f);
    }
}
