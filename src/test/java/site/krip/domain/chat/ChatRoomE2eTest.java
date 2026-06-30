package site.krip.domain.chat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 채팅 방 생성/멤버십/조회 REST E2E ({@code /api/chat/rooms}). 친구/차단은 리포지토리로 직접 시드,
 * 응답 JSON 은 snake_case, room type 은 소문자 enum({@code direct/group}).
 */
@DisplayName("채팅 방 — 1:1/그룹 생성·초대·퇴장·강퇴·조회")
class ChatRoomE2eTest extends ChatTestSupport {

    // ──────────────────── 1:1 방 ────────────────────

    @Test
    @DisplayName("1:1 방 생성 → 201, type=DIRECT + peer 노출; 재생성 시 동일 방(idempotent)")
    void createDirectIdempotent() throws Exception {
        String a = fixtures.createActiveUser("앨리스");
        String b = fixtures.createActiveUser("밥");

        MvcResult res = createDirect(a, b);
        assertThat(res.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = readJson(res);
        String roomId = body.get("chat_room_id").asText();
        assertThat(body.get("type").asText()).isEqualTo("direct");
        assertThat(body.get("peer").get("user_id").asText()).isEqualTo(b);

        // 재생성 → 같은 room_id (canonical UNIQUE 로 idempotent)
        MvcResult again = createDirect(a, b);
        assertThat(again.getResponse().getStatus()).isEqualTo(201);
        assertThat(idFrom(again, "chat_room_id")).isEqualTo(roomId);

        // 반대 방향(B→A)도 동일 방 (canonical 정렬)
        MvcResult reverse = createDirect(b, a);
        assertThat(idFrom(reverse, "chat_room_id")).isEqualTo(roomId);
    }

    @Test
    @DisplayName("비친구와도 1:1 방 생성 허용 → 201 (친구 정책 없음)")
    void createDirectNonFriendAllowed() throws Exception {
        String a = fixtures.createActiveUser("찰리");
        String b = fixtures.createActiveUser("데이브");

        assertThat(createDirect(a, b).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName("자기 자신과 1:1 방 → 400")
    void createDirectSelf() throws Exception {
        String a = fixtures.createActiveUser("이브");

        MvcResult res = createDirect(a, a);
        assertThat(res.getResponse().getStatus()).isEqualTo(400);
        assertThat(readJson(res).has("detail")).isTrue();
    }

    @Test
    @DisplayName("존재하지 않는 상대와 1:1 방 → 400")
    void createDirectNonexistentPeer() throws Exception {
        String a = fixtures.createActiveUser("프랭크");

        MvcResult res = createDirect(a, "nonexistent-user-id");
        assertThat(res.getResponse().getStatus()).isEqualTo(400);
        assertThat(readJson(res).has("detail")).isTrue();
    }

    @Test
    @DisplayName("차단한 상대와 1:1 방 → 400")
    void createDirectBlockedPeer() throws Exception {
        String a = fixtures.createActiveUser("그레이스");
        String b = fixtures.createActiveUser("헨리");
        block(a, b);

        MvcResult res = createDirect(a, b);
        assertThat(res.getResponse().getStatus()).isEqualTo(400);
        assertThat(readJson(res).has("detail")).isTrue();
    }

    @Test
    @DisplayName("peer_user_id 누락 → 400")
    void createDirectMissingPeer() throws Exception {
        String a = fixtures.createActiveUser("아이비");

        mockMvc.perform(post("/api/chat/rooms/direct")
                        .with(auth(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json()))
                .andExpect(status().isBadRequest());
    }

    // ──────────────────── 그룹 방 ────────────────────

    @Test
    @DisplayName("그룹 방 생성(친구 멤버) → 201, type=GROUP + title")
    void createGroupOk() throws Exception {
        String owner = fixtures.createActiveUser("방장");
        String m1 = fixtures.createActiveUser("멤버1");
        String m2 = fixtures.createActiveUser("멤버2");
        makeFriends(owner, m1);
        makeFriends(owner, m2);

        String roomId = createGroup(owner, "여행 단톡방", m1, m2);
        assertThat(roomId).isNotNull();

        // 방 상세 — GROUP + title
        mockMvc.perform(get("/api/chat/rooms/{id}", roomId)
                        .with(auth(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("group"))
                .andExpect(jsonPath("$.title").value("여행 단톡방"));
    }

    @Test
    @DisplayName("본인만 멤버인 그룹 방(=본인 제외 시 빈 멤버) → 400")
    void createGroupSelfOnly() throws Exception {
        String owner = fixtures.createActiveUser("외톨이");

        mockMvc.perform(post("/api/chat/rooms/group")
                        .with(auth(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("title", "혼자방", "member_ids", List.of(owner))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("친구가 아닌 유저를 그룹 멤버로 → 400")
    void createGroupNonFriend() throws Exception {
        String owner = fixtures.createActiveUser("주최자");
        String stranger = fixtures.createActiveUser("낯선이");

        mockMvc.perform(post("/api/chat/rooms/group")
                        .with(auth(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("title", "낯선방", "member_ids", List.of(stranger))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("member_ids 빈 배열 → 400 (검증)")
    void createGroupEmptyMembers() throws Exception {
        String owner = fixtures.createActiveUser("빈방장");

        mockMvc.perform(post("/api/chat/rooms/group")
                        .with(auth(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("title", "빈방", "member_ids", List.of())))
                .andExpect(status().isBadRequest());
    }

    // ──────────────────── 초대 / 퇴장 / 강퇴 ────────────────────

    @Test
    @DisplayName("그룹 방에 친구 초대 → invited 에 포함")
    void inviteFriend() throws Exception {
        String owner = fixtures.createActiveUser("초대장방장");
        String m1 = fixtures.createActiveUser("초대원1");
        String invitee = fixtures.createActiveUser("초대대상");
        makeFriends(owner, m1);
        makeFriends(owner, invitee);

        String roomId = createGroup(owner, "초대테스트", m1);

        mockMvc.perform(post("/api/chat/rooms/{id}/invite", roomId)
                        .with(auth(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("user_ids", List.of(invitee))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invited_user_ids[0]").value(invitee));
    }

    @Test
    @DisplayName("친구가 아닌 유저 초대 → 400")
    void inviteNonFriend() throws Exception {
        String owner = fixtures.createActiveUser("방장x");
        String m1 = fixtures.createActiveUser("기존멤버");
        String stranger = fixtures.createActiveUser("초대불가");
        makeFriends(owner, m1);

        String roomId = createGroup(owner, "초대거부", m1);

        mockMvc.perform(post("/api/chat/rooms/{id}/invite", roomId)
                        .with(auth(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("user_ids", List.of(stranger))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("비멤버가 초대 시도 → 403")
    void inviteByNonMember() throws Exception {
        String owner = fixtures.createActiveUser("진짜방장");
        String m1 = fixtures.createActiveUser("멤버a");
        String outsider = fixtures.createActiveUser("외부인");
        String target = fixtures.createActiveUser("타겟");
        makeFriends(owner, m1);
        makeFriends(outsider, target);

        String roomId = createGroup(owner, "권한방", m1);

        mockMvc.perform(post("/api/chat/rooms/{id}/invite", roomId)
                        .with(auth(outsider))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("user_ids", List.of(target))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("그룹 방 퇴장 → 204")
    void leaveGroup() throws Exception {
        String owner = fixtures.createActiveUser("퇴장방장");
        String leaver = fixtures.createActiveUser("나가는사람");
        makeFriends(owner, leaver);

        String roomId = createGroup(owner, "퇴장방", leaver);

        mockMvc.perform(post("/api/chat/rooms/{id}/leave", roomId)
                        .with(auth(leaver)))
                .andExpect(status().isNoContent());

        // 퇴장 후 방 상세 접근 → 403 (활성 멤버 아님)
        mockMvc.perform(get("/api/chat/rooms/{id}", roomId)
                        .with(auth(leaver)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("방장이 멤버 강퇴 → 204")
    void kickByOwner() throws Exception {
        String owner = fixtures.createActiveUser("강퇴방장");
        String target = fixtures.createActiveUser("강퇴대상");
        makeFriends(owner, target);

        String roomId = createGroup(owner, "강퇴방", target);

        mockMvc.perform(post("/api/chat/rooms/{id}/kick", roomId)
                        .with(auth(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("user_id", target)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("방장이 아닌 멤버가 강퇴 시도 → 403")
    void kickByNonOwner() throws Exception {
        String owner = fixtures.createActiveUser("원래방장");
        String m1 = fixtures.createActiveUser("일반멤버");
        String m2 = fixtures.createActiveUser("강퇴될뻔");
        makeFriends(owner, m1);
        makeFriends(owner, m2);

        String roomId = createGroup(owner, "강퇴권한방", m1, m2);

        // m1(방장 아님)이 m2 강퇴 시도 → 403
        mockMvc.perform(post("/api/chat/rooms/{id}/kick", roomId)
                        .with(auth(m1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("user_id", m2)))
                .andExpect(status().isForbidden());
    }

    // ──────────────────── 조회 ────────────────────

    @Test
    @DisplayName("방 리스트 조회 → 참여 중인 방 노출")
    void listRooms() throws Exception {
        String a = fixtures.createActiveUser("리스트a");
        String b = fixtures.createActiveUser("리스트b");

        MvcResult res = createDirect(a, b);
        String roomId = idFrom(res, "chat_room_id");

        mockMvc.perform(get("/api/chat/rooms")
                        .with(auth(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.chat_room_id == '" + roomId + "')]").exists());
    }

    @Test
    @DisplayName("그룹 방 참여자 목록 조회 → 멤버 노출")
    void listMembers() throws Exception {
        String owner = fixtures.createActiveUser("멤버조회방장");
        String m1 = fixtures.createActiveUser("조회멤버1");
        makeFriends(owner, m1);

        String roomId = createGroup(owner, "멤버조회방", m1);

        mockMvc.perform(get("/api/chat/rooms/{id}/members", roomId)
                        .with(auth(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.user_id == '" + m1 + "')]").exists())
                .andExpect(jsonPath("$.items[?(@.user_id == '" + owner + "')]").exists());
    }

    @Test
    @DisplayName("초대 가능 친구 목록 → 미참여 친구 노출 / 이미 멤버는 제외")
    void invitableFriends() throws Exception {
        String owner = fixtures.createActiveUser("초대가능방장");
        String member = fixtures.createActiveUser("이미멤버");
        String candidate = fixtures.createActiveUser("초대후보");
        makeFriends(owner, member);
        makeFriends(owner, candidate);

        String roomId = createGroup(owner, "초대가능방", member);

        mockMvc.perform(get("/api/chat/rooms/{id}/invitable-friends", roomId)
                        .with(auth(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.user_id == '" + candidate + "')]").exists())
                .andExpect(jsonPath("$.items[?(@.user_id == '" + member + "')]").doesNotExist());
    }

    @Test
    @DisplayName("비멤버가 방 상세 접근 → 403")
    void nonMemberGetRoom() throws Exception {
        String a = fixtures.createActiveUser("방주인");
        String b = fixtures.createActiveUser("방상대");
        String outsider = fixtures.createActiveUser("외부자");

        MvcResult res = createDirect(a, b);
        String roomId = idFrom(res, "chat_room_id");

        mockMvc.perform(get("/api/chat/rooms/{id}", roomId)
                        .with(auth(outsider)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("존재하지 않는 방 상세 → 404")
    void getRoomNotFound() throws Exception {
        String a = fixtures.createActiveUser("조회자");

        mockMvc.perform(get("/api/chat/rooms/{id}", "no-such-room")
                        .with(auth(a)))
                .andExpect(status().isNotFound());
    }
}
