package site.krip.domain.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import site.krip.domain.auth.entity.User;
import site.krip.domain.auth.repository.UserRepository;
import site.krip.domain.chat.entity.ChatRoomMember;
import site.krip.domain.chat.entity.ChatRoomMemberId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 알림 차단(mute) E2E — 경로 {@code /api/notification/mute}. 전역(유저) / 방별(멤버) 두 레벨.
 * 저장은 true 만, 해제는 NULL 정규화. 요청 JSON snake_case({@code muted}).
 */
@DisplayName("알림 뮤트 — 전역/방 토글·정규화·멤버십 검증")
class MuteE2eTest extends MuteTestSupport {

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 유저 전역 mute 의 raw 컬럼 값(NULL/true/false 구분) — getter 는 NULL 과 false 를 구분 못 함. */
    private Boolean rawGlobalMute(String userId) {
        return jdbcTemplate.queryForObject(
                "SELECT notification_muted FROM users WHERE user_id = ?", Boolean.class, userId);
    }

    // ──────────────────── 전역 mute ────────────────────

    @Test
    @DisplayName("전역 mute true 저장 → 200, true 영속")
    void globalMuteTruePersists() throws Exception {
        // given
        String userId = fixtures.createActiveUser("전역차단자");

        // when & then
        putGlobalMute(userId, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        User user = userRepo.findById(userId).orElseThrow();
        assertThat(user.isNotificationMuted()).as("전역 mute=true 가 영속되어야 한다").isTrue();
    }

    @Test
    @DisplayName("전역 mute false → 200, NULL 로 정규화(해제)")
    void globalMuteFalseClearsToNull() throws Exception {
        // given
        String userId = fixtures.createActiveUser("전역해제자");

        putGlobalMute(userId, true).andExpect(status().isOk());   // 먼저 차단
        putGlobalMute(userId, false).andExpect(status().isOk());  // 해제

        assertThat(rawGlobalMute(userId)).as("해제는 NULL 로 정규화되어야 한다").isNull();
    }

    @Test
    @DisplayName("전역 mute muted 필드 누락 → 400 (@NotNull)")
    void globalMuteMissingFieldBadRequest() throws Exception {
        // given
        String userId = fixtures.createActiveUser();

        // when & then
        mockMvc.perform(put("/api/notification/mute/global")
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json()))
                .andExpect(status().isBadRequest());
    }

    // ──────────────────── 방별 mute ────────────────────

    @Test
    @DisplayName("활성 멤버가 방 mute true → 200, 멤버 notification_muted=true")
    void roomMuteActiveMemberPersists() throws Exception {
        // given
        String userId = fixtures.createActiveUser("방멤버");
        String peerId = fixtures.createActiveUser("상대방");
        String roomId = seedRoomWithMember(userId, peerId);

        // when & then
        putRoomMute(userId, roomId, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        ChatRoomMember member = memberRepo.findById(new ChatRoomMemberId(roomId, userId)).orElseThrow();
        assertThat(member.getNotificationMuted()).as("방 mute=true 가 영속되어야 한다").isTrue();
    }

    @Test
    @DisplayName("활성 멤버가 방 mute false → 200, NULL 로 정규화")
    void roomMuteFalseClearsToNull() throws Exception {
        // given
        String userId = fixtures.createActiveUser("방멤버2");
        String peerId = fixtures.createActiveUser("상대방2");
        String roomId = seedRoomWithMember(userId, peerId);

        putRoomMute(userId, roomId, true).andExpect(status().isOk());
        putRoomMute(userId, roomId, false).andExpect(status().isOk());

        ChatRoomMember member = memberRepo.findById(new ChatRoomMemberId(roomId, userId)).orElseThrow();
        assertThat(member.getNotificationMuted()).as("해제는 NULL 로 정규화되어야 한다").isNull();
    }

    @Test
    @DisplayName("비멤버가 방 mute 시도 → 400 (활성 멤버 아님)")
    void roomMuteNonMemberBadRequest() throws Exception {
        // given
        String memberUser = fixtures.createActiveUser("진짜멤버");
        String peerId = fixtures.createActiveUser("상대방3");
        String outsider = fixtures.createActiveUser("외부인");
        String roomId = seedRoomWithMember(memberUser, peerId);

        // when & then
        putRoomMute(outsider, roomId, true).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("존재하지 않는 방 mute → 400 (멤버 행 없음)")
    void roomMuteNonExistentRoomBadRequest() throws Exception {
        // given
        String userId = fixtures.createActiveUser();

        // when & then
        putRoomMute(userId, "no-such-room", true).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("방 mute muted 필드 누락 → 400 (@NotNull)")
    void roomMuteMissingFieldBadRequest() throws Exception {
        // given
        String userId = fixtures.createActiveUser("방멤버3");
        String peerId = fixtures.createActiveUser("상대방4");
        String roomId = seedRoomWithMember(userId, peerId);

        // when & then
        mockMvc.perform(put("/api/notification/mute/rooms/{roomId}", roomId)
                        .with(auth(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json()))
                .andExpect(status().isBadRequest());
    }
}
