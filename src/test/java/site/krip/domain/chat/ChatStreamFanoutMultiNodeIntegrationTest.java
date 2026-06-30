package site.krip.domain.chat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import site.krip.support.IntegrationTestSupport;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 다중 노드 Stream fan-out — 격리 스트림에서 at-least-once(다운 중 발행분도 복귀 시 전부 수신) + ack 커서 전진을 검증. */
@DisplayName("스트림 fan-out 멀티노드 — 노드 재합류 시 유실 없는 갭 수신")
class ChatStreamFanoutMultiNodeIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private StringRedisTemplate redis;

    private String stream;
    private String group;

    @BeforeEach
    void setUp() {
        stream = "test:chat:stream:" + UUID.randomUUID();
        group = "nodeB";
        redis.opsForStream().createGroup(stream, ReadOffset.from("$"), group);
    }

    @AfterEach
    void tearDown() {
        redis.delete(stream);
    }

    @Test
    @DisplayName("노드가 끊긴 동안 발행된 이벤트도 group 복귀 시 모두 수신 — 유실 없음")
    void eventsPublishedWhileNodeDownAreRedelivered() {
        // node B 가 "다운"된 동안(아무도 안 읽음) node A 가 상태 이벤트들을 발행 → Stream 에 영속.
        publish("read:m1");
        publish("edit:m2");
        publish("delete:m3");

        // node B 복귀 → 자기 커서(>)부터 읽으면 끊긴 동안 것까지 전부 들어와야 한다.
        Set<String> received = readNew("c1");

        assertThat(received).containsExactlyInAnyOrder("read:m1", "edit:m2", "delete:m3");
    }

    @Test
    @DisplayName("group 커서는 ack 후 전진·유지 — 갭 이벤트만 이어 받고 재전송 없음")
    void cursorPersistsAndResumesFromLastPosition() {
        publish("first");
        assertThat(readNew("c1")).containsExactly("first"); // 읽고 ack → 커서 전진

        // "재시작" 사이에 발행된 이벤트.
        publish("second");

        // 새 consumer 가 같은 group 의 > 부터 — 전진한 커서 덕에 second 만, first 재전송 없음.
        assertThat(readNew("c2")).containsExactly("second");
    }

    private void publish(String marker) {
        redis.opsForStream().add(stream, Map.of("marker", marker));
    }

    /** group 의 새 메시지(>)를 읽어 ack 하고 marker 목록을 반환. */
    private Set<String> readNew(String consumer) {
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                Consumer.from(group, consumer),
                StreamReadOptions.empty().count(50),
                StreamOffset.create(stream, ReadOffset.lastConsumed()));
        Set<String> markers = new HashSet<>();
        if (records == null) {
            return markers;
        }
        for (MapRecord<String, Object, Object> rec : records) {
            redis.opsForStream().acknowledge(stream, group, rec.getId().getValue());
            Object marker = rec.getValue().get("marker");
            if (marker != null) {
                markers.add(marker.toString());
            }
        }
        return markers;
    }
}
