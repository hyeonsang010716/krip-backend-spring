package site.krip.domain.chat.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import site.krip.domain.chat.service.FanoutService;
import site.krip.global.config.ChatProperties;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * reaper 의 group 선정 로직 단위 테스트 — 스냅샷 경합/짧은 누락 방어(연속 부재 N틱 후에만 제거).
 */
@DisplayName("스트림 reaper — 연속 2틱 부재 시 제거·경합 방어")
class ChatStreamConfigReaperTest {

    private ChatStreamConfig config;

    @BeforeEach
    void setUp() {
        // ChatProperties(fanoutMode, nodeId, dedupeRedisDatabase, wsSendTimeLimitMs, wsSendBufferBytes, deliverySessionMaxQueued)
        ChatProperties props = new ChatProperties("redis_stream", "self", 1, 60_000, 1 << 20, 1000);
        config = new ChatStreamConfig(mock(StringRedisTemplate.class), mock(NodeRegistry.class),
                mock(FanoutService.class), props, new ObjectMapper());
    }

    @Test
    @DisplayName("부재 1틱째는 제거하지 않고, 연속 2틱째에 제거")
    void reapsOnlyAfterConsecutiveAbsence() {
        Set<String> active = Set.of("self");
        Set<String> existing = Set.of("self", "orphan");

        assertThat(config.selectGroupsToReap(active, existing)).isEmpty();         // 1틱: 유예
        assertThat(config.selectGroupsToReap(active, existing)).containsExactly("orphan"); // 2틱: 제거
    }

    @Test
    @DisplayName("합류 직후 한 틱 빠져도 다음 틱에 명단에 들어오면 제거되지 않는다(경합 방어)")
    void doesNotReapNodeThatRejoins() {
        Set<String> existing = Set.of("self", "joining");

        // 1틱: joining 이 스냅샷에 아직 없음(부재 1) — 제거 안 함.
        assertThat(config.selectGroupsToReap(Set.of("self"), existing)).isEmpty();
        // 2틱: joining 이 명단에 들어옴 → 카운트 리셋.
        assertThat(config.selectGroupsToReap(Set.of("self", "joining"), existing)).isEmpty();
        // 다시 한 틱 빠져도 리셋됐으므로 부재 1 — 제거 안 함.
        assertThat(config.selectGroupsToReap(Set.of("self"), existing)).isEmpty();
    }

    @Test
    @DisplayName("사라진 group 의 잔여 부재 카운트는 정리된다")
    void prunesStreakForVanishedGroups() {
        // orphan 부재 1틱 누적.
        assertThat(config.selectGroupsToReap(Set.of("self"), Set.of("self", "orphan"))).isEmpty();
        // orphan 이 목록에서 사라짐 → 카운트 정리. 다시 나타나면 부재 1부터 — 즉시 제거되지 않음.
        assertThat(config.selectGroupsToReap(Set.of("self"), Set.of("self"))).isEmpty();
        assertThat(config.selectGroupsToReap(Set.of("self"), Set.of("self", "orphan"))).isEmpty();
    }
}
