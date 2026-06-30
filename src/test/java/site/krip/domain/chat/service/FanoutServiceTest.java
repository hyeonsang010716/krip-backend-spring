package site.krip.domain.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import site.krip.global.config.ChatProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FanoutService} 단위 테스트 (in_process 모드) — Redis 없이 로컬 메모리 직배송만 검증:
 * 자기-에코 skip, 유저 전 세션 전달, 닫힌 소켓 정리(가짜 WS 세션으로 송신 캡처).
 */
@DisplayName("fan-out 서비스 — 자기 에코 skip·세션 정리·register 경합")
class FanoutServiceTest {

    private FanoutService fanout;
    private final Map<WebSocketSession, List<String>> captured = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        ChatProperties props = new ChatProperties("in_process", "node-1", 1, 60_000, 1 << 20, 1000);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        // 동기 실행기(Runnable::run) — 송신을 인라인 유지해 단위 테스트가 결정적이게 한다.
        fanout = new FanoutService(props, redis, new ObjectMapper(), Runnable::run);
    }

    /** session_id/user_id/subscribed_rooms 속성을 가진 열린(또는 닫힌) 가짜 WS 세션. 송신을 captured 에 적재. */
    private WebSocketSession session(String sessionId, String userId, boolean open) {
        WebSocketSession ws = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(FanoutService.ATTR_SESSION_ID, sessionId);
        attrs.put(FanoutService.ATTR_USER_ID, userId);
        attrs.put(FanoutService.ATTR_ROOMS, ConcurrentHashMap.newKeySet());
        when(ws.getAttributes()).thenReturn(attrs);
        when(ws.isOpen()).thenReturn(open);
        List<String> sink = new ArrayList<>();
        captured.put(ws, sink);
        try {
            doAnswer(inv -> {
                sink.add(((TextMessage) inv.getArgument(0)).getPayload());
                return null;
            }).when(ws).sendMessage(any());
        } catch (Exception ignored) {
            // mock 설정엔 체크예외가 실제로 발생하지 않음
        }
        return ws;
    }

    private Map<String, Object> payload(String type, String senderSessionId) {
        Map<String, Object> p = new HashMap<>();
        p.put("type", type);
        if (senderSessionId != null) {
            p.put("sender_session_id", senderSessionId);
        }
        return p;
    }

    @Test
    @DisplayName("방 fan-out 은 sender_session_id 세션(자기 에코)을 건너뛴다")
    void roomFanoutSkipsSenderSession() {
        // given
        WebSocketSession sender = session("s-sender", "u1", true);
        WebSocketSession other = session("s-other", "u2", true);
        fanout.registerSession(sender);
        fanout.registerSession(other);
        fanout.registerWsToRoom(sender, "room-1");
        fanout.registerWsToRoom(other, "room-1");

        // when
        fanout.fanOutToRoom("room-1", payload("message.new", "s-sender"));

        // then
        assertThat(captured.get(sender)).isEmpty();
        assertThat(captured.get(other)).hasSize(1);
        assertThat(captured.get(other).get(0)).contains("message.new");
    }

    @Test
    @DisplayName("유저 fan-out 은 해당 유저의 모든 세션에 전달된다")
    void userFanoutDeliversToAllSessions() {
        // given
        WebSocketSession s1 = session("s1", "u1", true);
        WebSocketSession s2 = session("s2", "u1", true);
        WebSocketSession otherUser = session("s3", "u2", true);
        fanout.registerSession(s1);
        fanout.registerSession(s2);
        fanout.registerSession(otherUser);

        // when
        fanout.fanOutToUser("u1", payload("room_left", null));

        // then
        assertThat(captured.get(s1)).hasSize(1);
        assertThat(captured.get(s2)).hasSize(1);
        assertThat(captured.get(otherUser)).isEmpty();
    }

    @Test
    @DisplayName("닫힌 소켓은 전달에서 제외되고 등록에서 정리된다")
    void closedSocketSkippedAndUnregistered() {
        // given
        WebSocketSession open = session("s-open", "u1", true);
        WebSocketSession closed = session("s-closed", "u2", false);
        fanout.registerSession(open);
        fanout.registerSession(closed);
        fanout.registerWsToRoom(open, "room-1");
        fanout.registerWsToRoom(closed, "room-1");

        // when
        fanout.fanOutToRoom("room-1", payload("message.new", null));

        // then
        assertThat(captured.get(open)).hasSize(1);
        assertThat(captured.get(closed)).isEmpty();

        // 정리되었으므로 이후 유저 fan-out 에도 닫힌 세션은 더 이상 대상이 아니다.
        fanout.fanOutToUser("u2", payload("ping", null));
        assertThat(captured.get(closed)).isEmpty();
    }

    @Test
    @DisplayName("unregisterWs 후에는 방 fan-out 대상에서 빠진다")
    void unregisterRemovesFromRoom() {
        // given
        WebSocketSession ws = session("s1", "u1", true);
        fanout.registerSession(ws);
        fanout.registerWsToRoom(ws, "room-1");

        // when
        fanout.unregisterWs(ws);
        fanout.fanOutToRoom("room-1", payload("message.new", null));

        // then
        assertThat(captured.get(ws)).isEmpty();
    }

    @Test
    @DisplayName("동시 register/unregister 경합 — 갓 등록한 세션이 userSubs 에서 누락(orphan)되지 않는다")
    void concurrentRegisterUnregisterNoOrphan() throws Exception {
        // 같은 user 의 세션을 여러 스레드가 동시에 register/unregister. 막 등록한 세션은 fanOutToUser 가 반드시
        // 닿아야 한다 — 닿지 않으면 빈-set 키 제거 race 로 live 세션이 userSubs 에서 누락된 것(orphan).
        // given
        String uid = "race-user";
        Map<WebSocketSession, Set<String>> recvByWs = new ConcurrentHashMap<>();
        AtomicInteger marker = new AtomicInteger();
        AtomicReference<String> violation = new AtomicReference<>();

        int threads = 6;
        int iters = 400;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.execute(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iters && violation.get() == null; i++) {
                        WebSocketSession ws = raceSession(uid, recvByWs);
                        fanout.registerSession(ws);
                        String mk = "m" + marker.incrementAndGet();
                        fanout.fanOutToUser(uid, Map.of("type", mk));
                        boolean got = recvByWs.getOrDefault(ws, Set.of()).stream()
                                .anyMatch(j -> j.contains("\"type\":\"" + mk + "\""));
                        if (!got) {
                            violation.compareAndSet(null, "registered session missed fan-out: " + mk);
                        }
                        fanout.unregisterWs(ws);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        // when
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

        // then
        assertThat(violation.get()).isNull();
        pool.shutdownNow();
    }

    /** 고유 sessionId + 공유 uid 를 가진 mock 세션. 수신 메시지를 thread-safe set 에 적재. */
    private WebSocketSession raceSession(String uid, Map<WebSocketSession, Set<String>> recvByWs) {
        WebSocketSession ws = mock(WebSocketSession.class);
        Map<String, Object> attrs = new ConcurrentHashMap<>();
        attrs.put(FanoutService.ATTR_SESSION_ID, "s-" + UUID.randomUUID());
        attrs.put(FanoutService.ATTR_USER_ID, uid);
        attrs.put(FanoutService.ATTR_ROOMS, ConcurrentHashMap.newKeySet());
        when(ws.getAttributes()).thenReturn(attrs);
        when(ws.isOpen()).thenReturn(true);
        Set<String> recv = ConcurrentHashMap.newKeySet();
        recvByWs.put(ws, recv);
        try {
            doAnswer(inv -> {
                recv.add(((TextMessage) inv.getArgument(0)).getPayload());
                return null;
            }).when(ws).sendMessage(any());
        } catch (Exception ignored) {
            // mock 설정엔 체크예외가 실제로 발생하지 않음
        }
        return ws;
    }
}
