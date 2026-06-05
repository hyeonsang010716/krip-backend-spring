package site.krip.domain.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import site.krip.domain.chat.repository.ChatMessageRepository;
import site.krip.domain.chat.repository.ChatRoomMemberRepository;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link UnreadRecoveryService} 순수 단위 테스트.
 *
 * <p>방별 Mongo count 는 독립 격리돼야 한다 — 한 방의 조회 실패가 전체 복구를
 * 중단시키면 안 되고, 나머지 방은 정상 복구돼야 한다.
 */
class UnreadRecoveryServiceTest {

    private final ChatRoomMemberRepository memberRepo = mock(ChatRoomMemberRepository.class);
    private final ChatMessageRepository messageRepo = mock(ChatMessageRepository.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);

    private final UnreadRecoveryService service =
            new UnreadRecoveryService(memberRepo, messageRepo, redis);

    private void seedRooms() {
        when(memberRepo.findLastReadSeqsAll("U")).thenReturn(List.of(
                new Object[]{"R1", 0L},
                new Object[]{"R2", 0L},
                new Object[]{"R3", 0L}));
        when(redis.opsForHash()).thenReturn(hashOps);
    }

    @Test
    @DisplayName("한 방의 Mongo count 실패 시 나머지 방은 정상 복구된다 (실패 방만 skip)")
    void perRoomFailureIsIsolated() {
        seedRooms();
        when(messageRepo.countAfterSeq(eq("R1"), anyLong(), anyInt())).thenReturn(5L);
        when(messageRepo.countAfterSeq(eq("R2"), anyLong(), anyInt()))
                .thenThrow(new RuntimeException("mongo down"));
        when(messageRepo.countAfterSeq(eq("R3"), anyLong(), anyInt())).thenReturn(7L);

        Map<String, Integer> result = service.recoverUnreadForUser("U");

        assertThat(result)
                .containsEntry("R1", 5)
                .containsEntry("R3", 7)
                .doesNotContainKey("R2");
        verify(hashOps).put(anyString(), eq("R1"), eq("5"));
        verify(hashOps).put(anyString(), eq("R3"), eq("7"));
        verify(hashOps, never()).put(anyString(), eq("R2"), anyString());
    }

    @Test
    @DisplayName("모든 방의 count 가 실패하면 빈 map 반환 + Redis 미기록")
    void allRoomsFailReturnsEmpty() {
        seedRooms();
        when(messageRepo.countAfterSeq(anyString(), anyLong(), anyInt()))
                .thenThrow(new RuntimeException("mongo down"));

        Map<String, Integer> result = service.recoverUnreadForUser("U");

        assertThat(result).isEmpty();
        verify(hashOps, never()).put(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("999 캡: count 가 캡을 초과해도 999 로 클램프된다")
    void countCappedAt999() {
        seedRooms();
        when(messageRepo.countAfterSeq(anyString(), anyLong(), anyInt())).thenReturn(1000L);

        Map<String, Integer> result = service.recoverUnreadForUser("U");

        assertThat(result).containsEntry("R1", 999).containsEntry("R2", 999).containsEntry("R3", 999);
    }
}
