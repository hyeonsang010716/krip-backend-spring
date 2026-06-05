package site.krip.domain.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import site.krip.domain.chat.repository.ChatMessageRepository;
import site.krip.domain.chat.repository.ChatRoomMemberRepository;
import site.krip.global.chat.ChatRedisKeys;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * unread 복구.
 *
 * <p>Redis flush/장애 또는 재초대 후, RDB {@code last_read_*} + Mongo 메시지 수로 {@code unread:{uid}} 재계산.
 * WS 재접속 시 Redis 가 비면 백그라운드로 트리거. 카톡 관례 999+ 캡. Redis 반영 실패 시 DEL 로 전체 정리해 재시도 유도.
 */
@Service
public class UnreadRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(UnreadRecoveryService.class);
    private static final int UNREAD_COUNT_CAP = 999;
    private static final int UNREAD_COUNT_LIMIT = UNREAD_COUNT_CAP + 1;

    private final ChatRoomMemberRepository memberRepo;
    private final ChatMessageRepository messageRepo;
    private final StringRedisTemplate redis;

    public UnreadRecoveryService(ChatRoomMemberRepository memberRepo, ChatMessageRepository messageRepo,
                                 StringRedisTemplate redis) {
        this.memberRepo = memberRepo;
        this.messageRepo = messageRepo;
        this.redis = redis;
    }

    /** 유저 전체 활성 방의 unread 재계산 후 HSET. 실패 시 빈 map. */
    public Map<String, Integer> recoverUnreadForUser(String userId) {
        Map<String, Long> lastReads = loadLastReads(userId);
        if (lastReads.isEmpty()) {
            return Map.of();
        }

        // 방별 Mongo count 는 독립 격리 — 한 방의 조회 실패가 전체 복구를 중단시키지 않도록 로그 후 skip.
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (var e : lastReads.entrySet()) {
            try {
                long raw = messageRepo.countAfterSeq(e.getKey(), e.getValue(), UNREAD_COUNT_LIMIT);
                counts.put(e.getKey(), (int) Math.min(raw, UNREAD_COUNT_CAP));
            } catch (Exception ex) {
                log.warn("recover_unread: user_id={} 방 count 실패 (skip): room_id={}, err={}",
                        userId, e.getKey(), ex.toString());
            }
        }
        if (counts.isEmpty()) {
            return Map.of();
        }

        try {
            for (var e : counts.entrySet()) {
                redis.opsForHash().put(ChatRedisKeys.unread(userId), e.getKey(), String.valueOf(e.getValue()));
            }
        } catch (Exception ex) {
            // partial state 가 남으면 다음 재연결의 get_unread_counts 가 non-empty 라 재복구 안 됨 → DEL 로 강제 초기화.
            log.warn("recover_unread: Redis 반영 실패 — partial state 정리: user_id={}, err={}",
                    userId, ex.toString());
            try {
                redis.delete(ChatRedisKeys.unread(userId));
            } catch (Exception delErr) {
                log.warn("recover_unread: cleanup DEL 실패 — partial state 잔존 위험: {}", delErr.toString());
            }
            return Map.of();
        }
        log.info("recover_unread: user_id={}, rooms={}, recovered={}", userId, lastReads.size(), counts.size());
        return counts;
    }

    // 스칼라 투영(room_id, seq) 단건 쿼리라 엔티티 lazy-init 가 없어 별도 트랜잭션 경계가 불필요하다.
    // (Spring Data 리포지토리 호출 자체가 트랜잭션) — self-invocation 으로 무효화되던 @Transactional 제거.
    private Map<String, Long> loadLastReads(String userId) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Object[] row : memberRepo.findLastReadSeqsAll(userId)) {
            String roomId = (String) row[0];
            Long seq = (Long) row[1];
            result.put(roomId, seq != null ? seq : 0L);
        }
        return result;
    }
}
