package site.krip.domain.chat.worker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import site.krip.domain.chat.repository.ChatMessageRepository;
import site.krip.domain.chat.repository.ChatMessageRepository.LastMessage;
import site.krip.domain.chat.repository.ChatRoomRepository;
import site.krip.global.chat.ChatRedisKeys;

import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ReconcileWorker#reconcileOnce()} 단위 테스트 — at-least-once drain.
 *
 * <p>검증: ① 빈 dirty → no-op ② 정상 배치 → RDB 갱신 + 해소분 SREM ③ Mongo aggregate 실패 → set 유지(재시도)
 * ④ 방별 UPDATE 실패 → 실패분은 set 에 유지(다음 tick 재시도) ⑤ tick 배치 예산 상한.
 */
class ReconcileWorkerTest {

    private static final String KEY = ChatRedisKeys.DIRTY_CHAT_ROOM_KEY;

    private StringRedisTemplate redis;
    private SetOperations<String, String> setOps;
    private ChatMessageRepository messageRepo;
    private ChatRoomRepository roomRepo;
    private ReconcileWorker worker;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        setOps = mock(SetOperations.class);
        messageRepo = mock(ChatMessageRepository.class);
        roomRepo = mock(ChatRoomRepository.class);
        when(redis.opsForSet()).thenReturn(setOps);
        worker = new ReconcileWorker(redis, messageRepo, roomRepo);
    }

    @Test
    @DisplayName("dirty 가 비면 0 반환, 저장소 미접근")
    void emptyDirtyIsNoOp() {
        when(setOps.distinctRandomMembers(eq(KEY), anyLong())).thenReturn(Set.of());

        assertThat(worker.reconcileOnce()).isZero();
        verifyNoInteractions(messageRepo, roomRepo);
    }

    @Test
    @DisplayName("정상: 읽은 방의 last_message 를 Mongo 값으로 갱신하고 해소분만 SREM")
    void happyPathUpdatesAndRemoves() {
        when(setOps.distinctRandomMembers(eq(KEY), anyLong())).thenReturn(Set.of("r1"));
        when(messageRepo.findLastByRooms(List.of("r1")))
                .thenReturn(Map.of("r1", new LastMessage("m1", 5L, new Date())));

        assertThat(worker.reconcileOnce()).isEqualTo(1);
        verify(roomRepo).updateLastMessageIfGreater(eq("r1"), eq("m1"), eq(5L), any());
        verify(setOps).remove(KEY, "r1");
    }

    @Test
    @DisplayName("Mongo aggregate 실패 → set 에 그대로 유지(SREM 없음), 0 반환")
    void mongoFailureKeepsInSet() {
        when(setOps.distinctRandomMembers(eq(KEY), anyLong())).thenReturn(Set.of("r1", "r2"));
        when(messageRepo.findLastByRooms(any())).thenThrow(new RuntimeException("mongo down"));

        assertThat(worker.reconcileOnce()).isZero();
        verify(setOps, never()).remove(any(), any());
        verify(roomRepo, never()).updateLastMessageIfGreater(any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("방별 UPDATE 실패 → 실패분은 set 에 유지(SREM 없음)")
    void perRoomFailureKeepsInSet() {
        when(setOps.distinctRandomMembers(eq(KEY), anyLong())).thenReturn(Set.of("r1"));
        when(messageRepo.findLastByRooms(List.of("r1")))
                .thenReturn(Map.of("r1", new LastMessage("m1", 5L, new Date())));
        doThrow(new RuntimeException("rdb down"))
                .when(roomRepo).updateLastMessageIfGreater(eq("r1"), any(), anyLong(), any());

        assertThat(worker.reconcileOnce()).isZero();
        verify(setOps, never()).remove(any(), any());
    }

    @Test
    @Timeout(10)
    @DisplayName("백로그가 끝없어도 tick 은 배치 예산(50회)에서 멈춘다 — 무한 drain 방지")
    void tickIsBoundedByBatchBudget() {
        // 매 배치가 가득 차고(500) Mongo hit 0 → 전건 해소 → reconcileOnce 가 매번 BATCH_SIZE 반환 → 끝없이 drain 가능
        Set<String> full = IntStream.range(0, 500).mapToObj(i -> "r" + i)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        when(setOps.distinctRandomMembers(eq(KEY), anyLong())).thenReturn(full);
        when(messageRepo.findLastByRooms(any())).thenReturn(Map.of());

        worker.reconcileTick();

        // MAX_BATCHES_PER_TICK(50) 에서 멈춰야 한다 — 무한 루프면 @Timeout 으로 실패.
        verify(setOps, times(50)).distinctRandomMembers(eq(KEY), anyLong());
    }
}
