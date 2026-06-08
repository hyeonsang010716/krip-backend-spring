package site.krip.domain.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import site.krip.domain.chat.dto.response.MessageSentAck;
import site.krip.domain.chat.entity.MessageType;
import site.krip.domain.chat.repository.ChatMessageRepository;
import site.krip.domain.friend.service.UserBlockService;
import site.krip.global.chat.ChatRedisKeys;
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

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    @Qualifier("dedupeRedisTemplate")
    private StringRedisTemplate dedupeRedis;

    @Autowired
    private ChatMessageRepository messageRepo;

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
    @DisplayName("dedupe: 동일 client_msg_id 재전송은 멱등 — 같은 메시지 ack 반환(중복 저장 없음)")
    void dedupeReturnsIdempotentAck() {
        String a = fixtures.createActiveUser("dedupeA");
        String b = fixtures.createActiveUser("dedupeB");
        String room = directRoom(a, b);

        MessageSentAck first = messageService.sendMessage(a, "sess-a", room, "dup-1", MessageType.TEXT, "first");
        MessageSentAck second = messageService.sendMessage(a, "sess-a", room, "dup-1", MessageType.TEXT, "second");

        // 재전송은 원본 ack 를 그대로 돌려준다(멱등) — content "second" 는 무시되고 저장은 1건뿐.
        assertThat(second.messageId()).isEqualTo(first.messageId());
        assertThat(second.serverSeq()).isEqualTo(first.serverSeq());
    }

    @Test
    @DisplayName("dedupe 키만 남고 메시지는 미저장(크래시 윈도우)이면 재전송이 손실 없이 저장된다")
    void staleDedupeKeyDoesNotLoseMessage() {
        String a = fixtures.createActiveUser("staleA");
        String b = fixtures.createActiveUser("staleB");
        String room = directRoom(a, b);

        // 크래시 윈도우 시뮬: dedupe 키만 미리 점유되고 메시지는 Mongo 에 없는 상태.
        dedupeRedis.opsForValue().set(ChatRedisKeys.dedupe(a, "cmid-1"), "1");

        // 재전송 — Redis 키만으로 거부되지 않고 Mongo 진실 기준으로 정상 저장돼야 한다.
        MessageSentAck ack = messageService.sendMessage(a, "sess-a", room, "cmid-1", MessageType.TEXT, "hi");

        assertThat(ack.messageId()).isNotNull();
        assertThat(messageRepo.findByClientMsgId(room, a, "cmid-1")).isNotNull();
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

    @Test
    @DisplayName("room:members 캐시가 유실돼도 새 메시지는 수신자 unread 를 무효화한다 (DB 폴백)")
    void unreadInvalidatedWhenMemberCacheLost() {
        String a = fixtures.createActiveUser("memCacheA");
        String b = fixtures.createActiveUser("memCacheB");
        String room = directRoom(a, b);

        // 수신자 b 의 unread 캐시에 stale 값을 심고, room:members 캐시를 유실시킨다(TTL 만료 시뮬).
        redis.opsForHash().put(ChatRedisKeys.unread(b), room, "5");
        redis.delete(ChatRedisKeys.roomMembers(room));

        messageService.sendMessage(a, "sess-a", room, "m1", MessageType.TEXT, "hi");

        // DB 폴백으로 멤버를 해석해 b 의 stale unread 캐시가 무효화(삭제)되어야 한다.
        assertThat(redis.opsForHash().get(ChatRedisKeys.unread(b), room)).isNull();
    }
}
