package site.krip.domain.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import site.krip.domain.chat.dto.response.EditMessageResponse;
import site.krip.domain.chat.dto.response.MessageSentAck;
import site.krip.domain.chat.entity.MessageType;
import site.krip.domain.chat.repository.ChatMessageRepository;
import site.krip.domain.friend.service.UserBlockService;
import site.krip.global.chat.ChatRedisKeys;
import site.krip.global.common.exception.ApiException;
import site.krip.support.IntegrationTestSupport;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 채팅 메시지 핫패스 서비스 통합 테스트 — WS 핸들러를 거치지 않고 {@link MessageService} 를 직접 호출해
 * 실 Redis(rate limit / dedupe / seq) + 실 Mongo(insert) 경로를 검증한다.
 */
@DisplayName("메시지 전송 서비스 — rate limit·dedupe·seq·차단·캐시 폴백")
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
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(400);
                    assertThat(ex.getMessage()).contains("속도 제한");
                });
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
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(403);
                    assertThat(ex.getMessage()).contains("차단");
                });
    }

    @Test
    @DisplayName("room:seq 키는 송신 시 TTL 이 설정된다(sliding) — 유휴 방 자연 회수, 영구 잔존 방지")
    void roomSeqKeyHasSlidingTtl() {
        String a = fixtures.createActiveUser("ttlA");
        String b = fixtures.createActiveUser("ttlB");
        String room = directRoom(a, b);

        messageService.sendMessage(a, "sess-a", room, "t1", MessageType.TEXT, "hi");

        Long ttl = redis.getExpire(ChatRedisKeys.roomSeq(room));
        assertThat(ttl).isNotNull().isGreaterThan(0L);
    }

    @Test
    @DisplayName("room:seq 만료(삭제) 후 재전송은 Mongo max+gap 으로 복구 — seq 증가(충돌/역행 없음) + TTL 재설정")
    void roomSeqRecoversAfterExpiry() {
        String a = fixtures.createActiveUser("recA");
        String b = fixtures.createActiveUser("recB");
        String room = directRoom(a, b);

        long s1 = messageService.sendMessage(a, "sess-a", room, "r1", MessageType.TEXT, "1").serverSeq();
        redis.delete(ChatRedisKeys.roomSeq(room)); // TTL 만료 시뮬

        long s2 = messageService.sendMessage(a, "sess-a", room, "r2", MessageType.TEXT, "2").serverSeq();

        assertThat(s2).isGreaterThan(s1);
        assertThat(redis.getExpire(ChatRedisKeys.roomSeq(room))).isGreaterThan(0L);
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

    @Test
    @DisplayName("편집/삭제 정상 경로: 본인 메시지 편집·삭제 성공, 삭제 후 편집은 차단")
    void editAndDeleteHappyPath() {
        String a = fixtures.createActiveUser("edhA");
        String b = fixtures.createActiveUser("edhB");
        String room = directRoom(a, b);

        String mid = messageService.sendMessage(a, "sess-a", room, "h1", MessageType.TEXT, "orig").messageId();

        EditMessageResponse edited = messageService.editMessage(mid, a, "sess-a", "updated");
        assertThat(edited.content()).isEqualTo("updated");

        messageService.deleteMessage(mid, a, "sess-a"); // 정상 — 예외 없음

        assertThatThrownBy(() -> messageService.editMessage(mid, a, "sess-a", "again"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("편집/삭제는 deleted_at:null doc 만 대상 — 삭제 후 재삭제·편집은 0 건(동시삭제 spurious fan-out 차단)")
    void editDeleteGatedOnDeletedDoc() {
        String a = fixtures.createActiveUser("edgA");
        String b = fixtures.createActiveUser("edgB");
        String room = directRoom(a, b);

        String mid = messageService.sendMessage(a, "sess-a", room, "g1", MessageType.TEXT, "hi").messageId();

        assertThat(messageRepo.softDelete(mid, new Date())).isTrue();        // 첫 삭제 = 1 건
        assertThat(messageRepo.softDelete(mid, new Date())).isFalse();       // 재삭제 = 0 건(idempotent)
        assertThat(messageRepo.updateContent(mid, "edited", new Date())).isFalse(); // 삭제된 doc 편집 = 0 건
    }
}
