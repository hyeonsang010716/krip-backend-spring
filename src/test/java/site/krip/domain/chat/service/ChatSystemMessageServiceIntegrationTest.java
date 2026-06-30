package site.krip.domain.chat.service;

import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import site.krip.domain.chat.entity.MessageType;
import site.krip.domain.chat.repository.ChatMessageRepository;
import site.krip.support.IntegrationTestSupport;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시스템 메시지 통합 테스트 — {@link MessageService#sendSystemMessage} 가 ① action/target 과 함께 Mongo 적재
 * ② unread 를 올리지 않음(일반 텍스트는 올림)을 실 Redis/Mongo 로 검증.
 */
@DisplayName("시스템 메시지 — 타입 적재·unread 미증가")
class ChatSystemMessageServiceIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private MessageService messageService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private MessageHistoryService historyService;

    @Autowired
    private ChatMessageRepository messageRepo;

    @Test
    @DisplayName("sendSystemMessage 는 system 타입 + action/target_ids 로 적재된다")
    void persistsSystemMessageWithActionAndTargets() {
        String a = fixtures.createActiveUser("sysA");
        String b = fixtures.createActiveUser("sysB");
        String room = roomService.createDirectRoom(a, b).chatRoomId();

        messageService.sendSystemMessage(room, "join", a, List.of(b));

        List<Document> docs = messageRepo.findAfter(room, 0, 100);
        Document sys = docs.stream()
                .filter(d -> MessageType.SYSTEM.getValue().equals(d.getString("type")))
                .reduce((first, second) -> second) // 마지막 system 메시지
                .orElseThrow(() -> new AssertionError("system 메시지가 적재되지 않음"));

        assertThat(sys.getString("sender_id")).isNull();
        Document content = (Document) sys.get("content");
        assertThat(content.getString("action")).isEqualTo("join");
        assertThat(content.getString("actor_id")).isEqualTo(a);
        assertThat(content.getList("target_ids", String.class)).containsExactly(b);
    }

    @Test
    @DisplayName("시스템 메시지는 unread 를 올리지 않지만, 일반 텍스트는 올린다")
    void systemMessageDoesNotBumpUnreadButTextDoes() {
        String a = fixtures.createActiveUser("bumpA");
        String b = fixtures.createActiveUser("bumpB");
        String room = roomService.createDirectRoom(a, b).chatRoomId();

        // 시스템 메시지 → b 의 unread 변동 없음
        messageService.sendSystemMessage(room, "join", a, List.of(b));
        assertThat(historyService.unreadCounts(b).getOrDefault(room, 0)).isZero();

        // 일반 텍스트 → b 의 unread 1 증가
        messageService.sendMessage(a, "sess-a", room, "c1", MessageType.TEXT, "안녕");
        assertThat(historyService.unreadCounts(b).getOrDefault(room, 0)).isEqualTo(1);
    }
}
