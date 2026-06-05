package site.krip.domain.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import site.krip.domain.chat.entity.MessageType;
import site.krip.domain.friend.service.UserBlockService;
import site.krip.global.common.exception.ApiException;
import site.krip.support.IntegrationTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 채팅 메시지 핫패스 서비스 통합 테스트 — WS 핸들러를 거치지 않고 {@link MessageService} 를 직접 호출해
 * 실 Redis(rate limit / dedupe / seq) + 실 Mongo(insert) 경로를 검증한다.
 */
class ChatMessageServiceIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private MessageService messageService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private UserBlockService userBlockService;

    private String directRoom(String a, String b) {
        return roomService.createDirectRoom(a, b).chatRoomId();
    }

    @Test
    @DisplayName("rate limit: 임계치(10)까지는 통과, 초과 시 400")
    void rateLimitBlocksAfterThreshold() {
        String a = fixtures.createActiveUser("rateA");
        String b = fixtures.createActiveUser("rateB");
        String room = directRoom(a, b);

        for (int i = 1; i <= 10; i++) {
            messageService.sendMessage(a, "sess-a", room, "c" + i, MessageType.TEXT, "msg" + i);
        }

        assertThatThrownBy(() ->
                messageService.sendMessage(a, "sess-a", room, "c11", MessageType.TEXT, "over"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("속도 제한");
    }

    @Test
    @DisplayName("dedupe: 동일 client_msg_id 재전송 시 400")
    void dedupeRejectsDuplicateClientMsgId() {
        String a = fixtures.createActiveUser("dedupeA");
        String b = fixtures.createActiveUser("dedupeB");
        String room = directRoom(a, b);

        messageService.sendMessage(a, "sess-a", room, "dup-1", MessageType.TEXT, "first");

        assertThatThrownBy(() ->
                messageService.sendMessage(a, "sess-a", room, "dup-1", MessageType.TEXT, "second"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("dedupe");
    }

    @Test
    @DisplayName("seq: 같은 방 연속 전송 시 server_seq 가 단조 증가한다")
    void serverSeqMonotonicallyIncreases() {
        String a = fixtures.createActiveUser("seqA");
        String b = fixtures.createActiveUser("seqB");
        String room = directRoom(a, b);

        long s1 = messageService.sendMessage(a, "sess-a", room, "s1", MessageType.TEXT, "1").serverSeq();
        long s2 = messageService.sendMessage(a, "sess-a", room, "s2", MessageType.TEXT, "2").serverSeq();
        long s3 = messageService.sendMessage(a, "sess-a", room, "s3", MessageType.TEXT, "3").serverSeq();

        assertThat(s2).isGreaterThan(s1);
        assertThat(s3).isGreaterThan(s2);
    }

    @Test
    @DisplayName("차단 핫패스: 1:1 방에서 차단 관계면 전송 403")
    void blockedDirectSendForbidden() {
        String a = fixtures.createActiveUser("blockA");
        String b = fixtures.createActiveUser("blockB");
        String room = directRoom(a, b);

        userBlockService.blockUser(a, b);

        assertThatThrownBy(() ->
                messageService.sendMessage(b, "sess-b", room, "x1", MessageType.TEXT, "hi"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("차단");
    }
}
