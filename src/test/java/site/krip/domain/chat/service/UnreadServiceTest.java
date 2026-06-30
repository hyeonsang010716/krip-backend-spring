package site.krip.domain.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import site.krip.domain.chat.repository.ChatMessageRepository;
import site.krip.domain.chat.repository.ChatRoomMemberRepository;
import site.krip.domain.chat.repository.LastReadSeq;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link UnreadService} 순수 단위 테스트 — miss 방은 단일 aggregate 로 배치 계산(N+1 제거), 캐시 hit 은 우회,
 * 미읽음 0 은 호출측이 채움, 배치 실패 시 miss 전체 skip(방별 격리 → all-or-nothing 변경).
 */
@DisplayName("미읽음 집계 — 배치 aggregate·캐시·999 캡")
class UnreadServiceTest {

    private final ChatRoomMemberRepository memberRepo = mock(ChatRoomMemberRepository.class);
    private final ChatMessageRepository messageRepo = mock(ChatMessageRepository.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);

    private final UnreadService service = new UnreadService(memberRepo, messageRepo, redis);

    private void seedRooms() {
        when(memberRepo.findLastReadSeqsAll("U")).thenReturn(List.of(
                new LastReadSeq("R1", 0L),
                new LastReadSeq("R2", 0L),
                new LastReadSeq("R3", 0L)));
        when(redis.opsForHash()).thenReturn(hashOps); // entries() 미스텁 → null → 전 방 miss 로 배치 계산
    }

    @Test
    @DisplayName("miss 방을 단일 aggregate 로 배치 계산하고 캐시에 기록한다")
    void batchComputesMissesAndCaches() {
        // given
        seedRooms();
        when(messageRepo.countAfterSeqByRooms(anyMap())).thenReturn(Map.of("R1", 5L, "R2", 2L, "R3", 7L));

        // when
        Map<String, Integer> result = service.countsForUser("U");

        // then
        assertThat(result).containsEntry("R1", 5).containsEntry("R2", 2).containsEntry("R3", 7);
        verify(messageRepo, times(1)).countAfterSeqByRooms(anyMap());
        verify(hashOps).put(anyString(), eq("R1"), eq("5"));
        verify(hashOps).put(anyString(), eq("R2"), eq("2"));
        verify(hashOps).put(anyString(), eq("R3"), eq("7"));
    }

    @Test
    @DisplayName("aggregate 결과에 없는 방(미읽음 0)은 0 으로 채워지고 0 으로 캐시된다")
    void roomsAbsentFromAggregateDefaultToZero() {
        // given
        seedRooms();
        // R2 는 미읽음 0 이라 aggregate 가 반환하지 않는다 → 호출측이 0 처리.
        when(messageRepo.countAfterSeqByRooms(anyMap())).thenReturn(Map.of("R1", 5L, "R3", 7L));

        // when
        Map<String, Integer> result = service.countsForUser("U");

        // then
        assertThat(result).containsEntry("R1", 5).containsEntry("R2", 0).containsEntry("R3", 7);
        verify(hashOps).put(anyString(), eq("R2"), eq("0"));
    }

    @Test
    @DisplayName("999 캡: aggregate count 가 캡을 초과해도 999 로 클램프된다")
    void countCappedAt999() {
        // given
        seedRooms();
        when(messageRepo.countAfterSeqByRooms(anyMap())).thenReturn(Map.of("R1", 1000L, "R2", 5000L, "R3", 1000L));

        // when
        Map<String, Integer> result = service.countsForUser("U");

        // then
        assertThat(result).containsEntry("R1", 999).containsEntry("R2", 999).containsEntry("R3", 999);
    }

    @Test
    @DisplayName("배치 aggregate 실패 시 miss 방 전체 skip — 빈 map 반환 + Redis 미기록")
    void batchFailureReturnsEmpty() {
        // given
        seedRooms();
        when(messageRepo.countAfterSeqByRooms(anyMap())).thenThrow(new RuntimeException("mongo down"));

        // when
        Map<String, Integer> result = service.countsForUser("U");

        // then
        assertThat(result).isEmpty();
        verify(hashOps, never()).put(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("캐시 hit 방은 배치를 우회하고, miss 방만 aggregate 로 계산된다")
    void cacheHitsBypassBatch() {
        // given
        when(memberRepo.findLastReadSeqsAll("U")).thenReturn(List.of(
                new LastReadSeq("R1", 0L),
                new LastReadSeq("R2", 0L),
                new LastReadSeq("R3", 0L)));
        when(redis.opsForHash()).thenReturn(hashOps);
        when(hashOps.entries(anyString())).thenReturn(Map.of("R1", "3")); // R1 만 캐시 hit
        // miss 인 R2/R3 만 배치로 넘어가야 한다.
        when(messageRepo.countAfterSeqByRooms(eq(Map.of("R2", 0L, "R3", 0L))))
                .thenReturn(Map.of("R2", 4L, "R3", 6L));

        // when
        Map<String, Integer> result = service.countsForUser("U");

        // then
        assertThat(result).containsEntry("R1", 3).containsEntry("R2", 4).containsEntry("R3", 6);
        verify(messageRepo, times(1)).countAfterSeqByRooms(eq(Map.of("R2", 0L, "R3", 0L)));
        // 캐시 hit 인 R1 은 재계산·재기록하지 않는다.
        verify(hashOps, never()).put(anyString(), eq("R1"), anyString());
        verify(hashOps).put(anyString(), eq("R2"), eq("4"));
        verify(hashOps).put(anyString(), eq("R3"), eq("6"));
    }
}
