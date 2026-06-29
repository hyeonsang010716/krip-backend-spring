package site.krip.domain.chat.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import site.krip.global.config.ChatProperties;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ChatStreamConfig 부팅 가드 — redis_stream(다중 노드)에서 미설정 기본 node-id('node-local'/공란)를
 * fail-fast 거부해 consumer group 공유로 인한 fan-out 붕괴를 방지한다.
 */
class ChatStreamConfigNodeIdGuardTest {

    // ChatProperties(fanoutMode, nodeId, dedupeRedisDatabase, wsSendTimeLimitMs, wsSendBufferBytes, deliverySessionMaxQueued)
    private static ChatProperties props(String mode, String nodeId) {
        return new ChatProperties(mode, nodeId, 1, 60_000, 1 << 20, 1000);
    }

    private static ChatStreamConfig construct(ChatProperties props) {
        return new ChatStreamConfig(null, null, null, props, new ObjectMapper());
    }

    @ParameterizedTest
    @ValueSource(strings = {"node-local", "   "})
    @DisplayName("redis_stream + 미설정 기본값('node-local')/공란 node-id → 부팅 실패")
    void rejectsUnconfiguredNodeIdInStreamMode(String nodeId) {
        assertThatThrownBy(() -> construct(props("redis_stream", nodeId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NODE_ID");
    }

    @Test
    @DisplayName("redis_stream + 명시적 고유 node-id → 정상 생성")
    void acceptsExplicitNodeIdInStreamMode() {
        assertThatCode(() -> construct(props("redis_stream", "node-7"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("in_process 모드면 'node-local' 이어도 허용 — 가드는 다중 노드 전용")
    void allowsNodeLocalWhenNotMultiNode() {
        assertThatCode(() -> construct(props("in_process", "node-local"))).doesNotThrowAnyException();
    }
}
