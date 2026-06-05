package site.krip.domain.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import site.krip.domain.chat.entity.MessageType;
import site.krip.domain.chat.repository.ChatMessageRepository;
import site.krip.support.IntegrationTestSupport;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 채팅 메시지 히스토리/편집/삭제 REST E2E ({@code /api/chat/rooms/{id}/messages}, {@code /api/chat/messages}).
 *
 * <p>메시지 본문 생성은 평시 WS 핫패스(seq 채번 + Mongo insert)로 일어나므로, 히스토리/편집/삭제
 * 검증에 필요한 {@code chat_message} Mongo 도큐먼트를 {@link ChatMessageRepository} 로 직접 시드한다
 * (도큐먼트 형태는 {@code MessageService.baseDoc} 와 동일). 방/멤버십은 1:1 방 생성 REST 로 만든다.
 */
class ChatMessageE2eTest extends IntegrationTestSupport {

    private final ObjectMapper om = new ObjectMapper();

    @Autowired
    private ChatMessageRepository messageRepo;

    // ──────────────────── 헬퍼 ────────────────────

    /** 1:1 방 생성 REST → room_id 반환 (a,b 양쪽 활성 멤버 + Redis 멤버셋 세팅). */
    private String createDirectRoom(String a, String b) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/chat/rooms/direct")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"peer_user_id\":\"" + b + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return om.readTree(res.getResponse().getContentAsString()).get("chat_room_id").asText();
    }

    /** chat_message Mongo 도큐먼트 시드 (MessageService.baseDoc 형태). createdAt 으로 5분 창 제어. */
    private String seedMessage(String roomId, long seq, String senderId, String content, Instant createdAt) {
        String messageId = "msg-" + roomId + "-" + seq;
        Document doc = new Document();
        doc.put("_id", messageId);
        doc.put("chat_room_id", roomId);
        doc.put("server_seq", seq);
        doc.put("sender_id", senderId);
        doc.put("type", MessageType.TEXT.getValue());
        doc.put("content", content);
        doc.put("created_at", Date.from(createdAt));
        doc.put("edited_at", null);
        doc.put("deleted_at", null);
        messageRepo.insert(doc);
        return messageId;
    }

    private String seedMessage(String roomId, long seq, String senderId, String content) {
        return seedMessage(roomId, seq, senderId, content, Instant.now());
    }

    // ──────────────────── 히스토리 조회 ────────────────────

    @Test
    @DisplayName("before_server_seq 로 과거 메시지 DESC 조회")
    void historyBefore() throws Exception {
        String a = fixtures.createActiveUser("히스토리a");
        String b = fixtures.createActiveUser("히스토리b");
        String roomId = createDirectRoom(a, b);

        seedMessage(roomId, 1, a, "첫번째");
        seedMessage(roomId, 2, b, "두번째");
        seedMessage(roomId, 3, a, "세번째");

        mockMvc.perform(get("/api/chat/rooms/{id}/messages", roomId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(a))
                        .param("before_server_seq", "10")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(3))
                // DESC — 가장 최신(seq=3)이 먼저
                .andExpect(jsonPath("$.messages[0].server_seq").value(3))
                .andExpect(jsonPath("$.messages[2].server_seq").value(1))
                .andExpect(jsonPath("$.has_more").value(false));
    }

    @Test
    @DisplayName("after_server_seq 로 이후 메시지 ASC 조회")
    void historyAfter() throws Exception {
        String a = fixtures.createActiveUser("이후a");
        String b = fixtures.createActiveUser("이후b");
        String roomId = createDirectRoom(a, b);

        seedMessage(roomId, 1, a, "m1");
        seedMessage(roomId, 2, b, "m2");
        seedMessage(roomId, 3, a, "m3");

        mockMvc.perform(get("/api/chat/rooms/{id}/messages", roomId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(a))
                        .param("after_server_seq", "1")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(2))
                // ASC — seq=2 먼저
                .andExpect(jsonPath("$.messages[0].server_seq").value(2))
                .andExpect(jsonPath("$.messages[1].server_seq").value(3));
    }

    @Test
    @DisplayName("limit 보다 많은 메시지 → has_more=true + next_cursor")
    void historyPagination() throws Exception {
        String a = fixtures.createActiveUser("페이지a");
        String b = fixtures.createActiveUser("페이지b");
        String roomId = createDirectRoom(a, b);

        for (long s = 1; s <= 5; s++) {
            seedMessage(roomId, s, a, "p" + s);
        }

        mockMvc.perform(get("/api/chat/rooms/{id}/messages", roomId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(a))
                        .param("before_server_seq", "100")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.has_more").value(true))
                .andExpect(jsonPath("$.next_cursor").exists());
    }

    @Test
    @DisplayName("before/after 둘 다 지정 → 400")
    void historyBothCursors() throws Exception {
        String a = fixtures.createActiveUser("둘다a");
        String b = fixtures.createActiveUser("둘다b");
        String roomId = createDirectRoom(a, b);

        mockMvc.perform(get("/api/chat/rooms/{id}/messages", roomId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(a))
                        .param("before_server_seq", "10")
                        .param("after_server_seq", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("before/after 둘 다 미지정 → 400")
    void historyNoCursor() throws Exception {
        String a = fixtures.createActiveUser("없음a");
        String b = fixtures.createActiveUser("없음b");
        String roomId = createDirectRoom(a, b);

        mockMvc.perform(get("/api/chat/rooms/{id}/messages", roomId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(a)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("limit 범위 밖(0) → 400")
    void historyLimitTooLow() throws Exception {
        String a = fixtures.createActiveUser("limit하a");
        String b = fixtures.createActiveUser("limit하b");
        String roomId = createDirectRoom(a, b);

        mockMvc.perform(get("/api/chat/rooms/{id}/messages", roomId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(a))
                        .param("before_server_seq", "10")
                        .param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("limit 범위 밖(201) → 400")
    void historyLimitTooHigh() throws Exception {
        String a = fixtures.createActiveUser("limit상a");
        String b = fixtures.createActiveUser("limit상b");
        String roomId = createDirectRoom(a, b);

        mockMvc.perform(get("/api/chat/rooms/{id}/messages", roomId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(a))
                        .param("before_server_seq", "10")
                        .param("limit", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("비멤버가 히스토리 조회 → 403")
    void historyNonMember() throws Exception {
        String a = fixtures.createActiveUser("멤버a");
        String b = fixtures.createActiveUser("멤버b");
        String outsider = fixtures.createActiveUser("비멤버");
        String roomId = createDirectRoom(a, b);
        seedMessage(roomId, 1, a, "secret");

        mockMvc.perform(get("/api/chat/rooms/{id}/messages", roomId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(outsider))
                        .param("before_server_seq", "10"))
                .andExpect(status().isForbidden());
    }

    // ──────────────────── 편집 ────────────────────

    @Test
    @DisplayName("본인 메시지 편집(5분 이내) → 200, content 갱신")
    void editOwnMessage() throws Exception {
        String a = fixtures.createActiveUser("편집a");
        String b = fixtures.createActiveUser("편집b");
        String roomId = createDirectRoom(a, b);
        String messageId = seedMessage(roomId, 1, a, "원본", Instant.now());

        mockMvc.perform(patch("/api/chat/messages/{id}", messageId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"수정됨\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message_id").value(messageId))
                .andExpect(jsonPath("$.content").value("수정됨"))
                .andExpect(jsonPath("$.edited_at").exists());
    }

    @Test
    @DisplayName("타인이 메시지 편집 시도 → 403")
    void editOthersMessage() throws Exception {
        String a = fixtures.createActiveUser("작성자");
        String b = fixtures.createActiveUser("타인");
        String roomId = createDirectRoom(a, b);
        String messageId = seedMessage(roomId, 1, a, "내것");

        mockMvc.perform(patch("/api/chat/messages/{id}", messageId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(b))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"해킹\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("5분 편집 제한 시간 경과 후 편집 → 400")
    void editAfterTimeLimit() throws Exception {
        String a = fixtures.createActiveUser("늦은편집a");
        String b = fixtures.createActiveUser("늦은편집b");
        String roomId = createDirectRoom(a, b);
        // 10분 전 작성 → 편집 제한 시간(5분) 초과
        String messageId = seedMessage(roomId, 1, a, "오래됨",
                Instant.now().minus(10, ChronoUnit.MINUTES));

        mockMvc.perform(patch("/api/chat/messages/{id}", messageId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"이미늦음\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("존재하지 않는 메시지 편집 → 400")
    void editNonexistentMessage() throws Exception {
        String a = fixtures.createActiveUser("편집없음");

        mockMvc.perform(patch("/api/chat/messages/{id}", "no-such-message")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"내용\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("content 빈 문자열 편집 → 400 (검증)")
    void editEmptyContent() throws Exception {
        String a = fixtures.createActiveUser("빈편집a");
        String b = fixtures.createActiveUser("빈편집b");
        String roomId = createDirectRoom(a, b);
        String messageId = seedMessage(roomId, 1, a, "원본");

        mockMvc.perform(patch("/api/chat/messages/{id}", messageId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    // ──────────────────── 삭제 ────────────────────

    @Test
    @DisplayName("본인 메시지 삭제 → 204")
    void deleteOwnMessage() throws Exception {
        String a = fixtures.createActiveUser("삭제a");
        String b = fixtures.createActiveUser("삭제b");
        String roomId = createDirectRoom(a, b);
        String messageId = seedMessage(roomId, 1, a, "삭제될메시지");

        mockMvc.perform(delete("/api/chat/messages/{id}", messageId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(a)))
                .andExpect(status().isNoContent());

        // 삭제 후 히스토리 조회 시 content=null (soft delete)
        mockMvc.perform(get("/api/chat/rooms/{id}/messages", roomId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(a))
                        .param("before_server_seq", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].deleted_at").exists())
                .andExpect(jsonPath("$.messages[0].content").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("타인이 1:1 메시지 삭제 시도 → 403")
    void deleteOthersMessage() throws Exception {
        String a = fixtures.createActiveUser("삭제작성자");
        String b = fixtures.createActiveUser("삭제타인");
        String roomId = createDirectRoom(a, b);
        String messageId = seedMessage(roomId, 1, a, "내메시지");

        mockMvc.perform(delete("/api/chat/messages/{id}", messageId)
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(b)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("존재하지 않는 메시지 삭제 → 400")
    void deleteNonexistentMessage() throws Exception {
        String a = fixtures.createActiveUser("삭제없음");

        mockMvc.perform(delete("/api/chat/messages/{id}", "no-such-message")
                        .header("Authorization", bearer())
                        .header("X-Auth-Token", userToken(a)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }
}
