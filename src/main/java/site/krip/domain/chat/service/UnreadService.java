package site.krip.domain.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import site.krip.domain.chat.repository.ChatMessageRepository;
import site.krip.domain.chat.repository.ChatRoomMemberRepository;
import site.krip.domain.chat.repository.LastReadSeq;
import site.krip.global.chat.ChatRedisKeys;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * unread = 커서 파생.
 *
 * <p>진실은 RDB {@code last_read_message_server_seq} + Mongo 메시지(system 제외)뿐이고,
 * {@code unread:{uid}} 해시는 재계산 가능한 캐시다. 절대 증분하지 않고 진실에서만 채운다 —
 * 읽기는 캐시 miss 인 방만 {@code countAfterSeq} 로 계산, 새 메시지는 수신자 캐시를 무효화한다.
 * 읽기 경로는 계산~기록 사이 {@code room:seq} 가 진전되면 캐시를 폐기해 stale lost-update 를 막는다.
 * 999 캡. 모든 Redis 접근은 best-effort.
 */
@Service
public class UnreadService {

    private static final Logger log = LoggerFactory.getLogger(UnreadService.class);
    private static final int UNREAD_COUNT_CAP = 999;
    private static final int UNREAD_COUNT_LIMIT = UNREAD_COUNT_CAP + 1;

    private final ChatRoomMemberRepository memberRepo;
    private final ChatMessageRepository messageRepo;
    private final StringRedisTemplate redis;

    public UnreadService(ChatRoomMemberRepository memberRepo, ChatMessageRepository messageRepo,
                         StringRedisTemplate redis) {
        this.memberRepo = memberRepo;
        this.messageRepo = messageRepo;
        this.redis = redis;
    }

    // ──────────────────── 읽기 (캐시 miss 인 방만 진실에서 계산) ────────────────────

    /** 유저 전체 활성 방의 unread. 캐시 hit 은 그대로, miss 인 방은 단일 aggregate 로 배치 계산 후 캐시. */
    public Map<String, Integer> countsForUser(String userId) {
        Map<String, Long> active = loadLastReads(userId);
        if (active.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> cached = readCache(userId);

        Map<String, Integer> result = new LinkedHashMap<>();
        Map<String, Long> misses = new LinkedHashMap<>();
        for (var e : active.entrySet()) {
            Integer hit = cached.get(e.getKey());
            if (hit != null) {
                result.put(e.getKey(), hit);
            } else {
                misses.put(e.getKey(), e.getValue());
            }
        }

        Map<String, Integer> computed = new LinkedHashMap<>();
        Map<String, Long> seqBefore = new LinkedHashMap<>();
        if (!misses.isEmpty()) {
            for (String roomId : misses.keySet()) {
                seqBefore.put(roomId, roomSeqSnapshot(roomId)); // 계산 전 방별 스냅샷 (lost-update 가드)
            }
            // 캐시 miss 인 방을 단일 aggregate 로 배치 계산 — 방마다 countDocuments 치던 N+1 제거.
            try {
                Map<String, Long> counts = messageRepo.countAfterSeqByRooms(misses);
                for (String roomId : misses.keySet()) {
                    int v = (int) Math.min(counts.getOrDefault(roomId, 0L), UNREAD_COUNT_CAP);
                    result.put(roomId, v);
                    computed.put(roomId, v);
                }
            } catch (Exception ex) {
                log.warn("unread 배치 계산 실패 (캐시분만 반환): user_id={}, err={}", userId, ex.toString());
            }
        }
        writeCache(userId, computed);
        // 계산~기록 사이 새 메시지로 진전된 방은 캐시 폐기 — 다음 읽기에 진실로 재계산.
        for (String roomId : computed.keySet()) {
            if (roomSeqSnapshot(roomId) > seqBefore.get(roomId)) {
                clear(userId, roomId);
            }
        }
        return result;
    }

    /** 단일 방 unread (방 상세 조회용). */
    public int countForRoom(String userId, String roomId) {
        try {
            Object raw = redis.opsForHash().get(ChatRedisKeys.unread(userId), roomId);
            if (raw != null) {
                return Integer.parseInt(raw.toString());
            }
        } catch (Exception ignore) {
            // 캐시 오류 → 계산으로 폴백
        }
        long seqBefore = roomSeqSnapshot(roomId); // 계산 전 스냅샷 (lost-update 가드)
        long lastRead = memberRepo.findLastReadSeq(roomId, userId).orElse(0L);
        int v;
        try {
            v = compute(roomId, lastRead);
        } catch (Exception e) {
            log.warn("unread 단건 계산 실패 (0): user_id={}, room_id={}", userId, roomId, e);
            return 0;
        }
        writeCache(userId, Map.of(roomId, v));
        if (roomSeqSnapshot(roomId) > seqBefore) {
            clear(userId, roomId); // 계산~기록 사이 새 메시지 유입 — stale 캐시 폐기
        }
        return v;
    }

    // ──────────────────── 쓰기 (진실 변경 시 캐시 동기화) ────────────────────

    /** 미읽음이 진짜 0 인 시점에 캐시를 0 으로 확정 — 신규 방/신규 멤버 입장, 최신까지 읽음 처리. */
    public void resetToZero(String userId, String roomId) {
        try {
            String key = ChatRedisKeys.unread(userId);
            redis.opsForHash().put(key, roomId, "0");
            redis.expire(key, Duration.ofSeconds(ChatRedisKeys.UNREAD_TTL));
        } catch (Exception e) {
            log.warn("unread reset 실패 (무시): user_id={}, room_id={}", userId, roomId, e.toString());
        }
    }

    /** 퇴장/재입장 — 캐시 제거 (다음 읽기에 진실로 재계산). */
    public void clear(String userId, String roomId) {
        try {
            redis.opsForHash().delete(ChatRedisKeys.unread(userId), roomId);
        } catch (Exception e) {
            log.warn("unread clear 실패 (무시): user_id={}, room_id={}", userId, roomId, e.toString());
        }
    }

    /** 새 메시지 — 수신자 캐시를 단일 파이프라인으로 무효화. 다음 읽기에 진실로 재계산. */
    public void invalidateForRecipients(String roomId, Collection<String> recipientUserIds) {
        if (recipientUserIds == null || recipientUserIds.isEmpty()) {
            return;
        }
        try {
            redis.executePipelined((RedisCallback<Object>) connection -> {
                StringRedisConnection conn = (StringRedisConnection) connection;
                for (String uid : recipientUserIds) {
                    conn.hDel(ChatRedisKeys.unread(uid), roomId);
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("unread 무효화 실패 (무시): room_id={}", roomId, e.toString());
        }
    }

    // ──────────────────── 내부 ────────────────────

    private int compute(String roomId, long lastRead) {
        long raw = messageRepo.countAfterSeq(roomId, lastRead, UNREAD_COUNT_LIMIT);
        return (int) Math.min(raw, UNREAD_COUNT_CAP);
    }

    /** room:seq 스냅샷 — 변경 감지 전용(부재/오류는 0). 유일 writer 가 allocateSeq(단조 증가)라 증가=새 메시지. */
    private long roomSeqSnapshot(String roomId) {
        try {
            String raw = redis.opsForValue().get(ChatRedisKeys.roomSeq(roomId));
            return raw != null ? Long.parseLong(raw) : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private Map<String, Long> loadLastReads(String userId) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (LastReadSeq row : memberRepo.findLastReadSeqsAll(userId)) {
            result.put(row.roomId(), row.seq() != null ? row.seq() : 0L);
        }
        return result;
    }

    private Map<String, Integer> readCache(String userId) {
        try {
            Map<Object, Object> raw = redis.opsForHash().entries(ChatRedisKeys.unread(userId));
            if (raw == null || raw.isEmpty()) {
                return Map.of();
            }
            Map<String, Integer> parsed = new HashMap<>();
            for (var e : raw.entrySet()) {
                try {
                    parsed.put(e.getKey().toString(), Integer.parseInt(e.getValue().toString()));
                } catch (NumberFormatException ignore) {
                    // 손상된 항목은 무시 → 해당 방은 miss 로 재계산
                }
            }
            return parsed;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private void writeCache(String userId, Map<String, Integer> values) {
        if (values.isEmpty()) {
            return;
        }
        try {
            String key = ChatRedisKeys.unread(userId);
            for (var e : values.entrySet()) {
                redis.opsForHash().put(key, e.getKey(), String.valueOf(e.getValue()));
            }
            redis.expire(key, Duration.ofSeconds(ChatRedisKeys.UNREAD_TTL));
        } catch (Exception e) {
            log.warn("unread 캐시 기록 실패 (무시): user_id={}, err={}", userId, e.toString());
        }
    }
}
