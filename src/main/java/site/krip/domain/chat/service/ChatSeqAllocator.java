package site.krip.domain.chat.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import site.krip.domain.chat.repository.ChatMessageRepository;
import site.krip.global.chat.ChatRedisKeys;

import java.util.List;

/**
 * 방 시퀀스 채번 + rate limit (Lua).
 *
 * <p>핫: incr_fast. 키 부재(Redis 리셋) 시 Mongo max(server_seq) + RECOVER_GAP 로 recover_and_incr.
 * Mongo DuplicateKey 재시도는 force_jump 로 seq 를 앞으로 점프(jitter 로 재충돌 확률↓).
 */
@Component
public class ChatSeqAllocator {

    private final StringRedisTemplate redis;
    private final ChatMessageRepository messageRepo;
    private final RedisScript<Long> incrFast;
    private final RedisScript<Long> recoverAndIncr;
    private final RedisScript<Long> forceJump;
    private final RedisScript<Long> incrWithTtl;

    public ChatSeqAllocator(StringRedisTemplate redis,
                            ChatMessageRepository messageRepo,
                            @Qualifier("incrFastScript") RedisScript<Long> incrFast,
                            @Qualifier("recoverAndIncrScript") RedisScript<Long> recoverAndIncr,
                            @Qualifier("forceJumpScript") RedisScript<Long> forceJump,
                            @Qualifier("incrWithTtlScript") RedisScript<Long> incrWithTtl) {
        this.redis = redis;
        this.messageRepo = messageRepo;
        this.incrFast = incrFast;
        this.recoverAndIncr = recoverAndIncr;
        this.forceJump = forceJump;
        this.incrWithTtl = incrWithTtl;
    }

    /** 다음 seq 채번 — fast-path 우선, 키 증발 시 Mongo 기반 복구. */
    public long allocateSeq(String roomId) {
        String seqKey = ChatRedisKeys.roomSeq(roomId);
        Long seq = redis.execute(incrFast, List.of(seqKey));
        if (seq != null && seq != -1L) {
            return seq;
        }
        long mongoMax = messageRepo.getMaxServerSeq(roomId);
        long base = mongoMax > 0 ? mongoMax + ChatRedisKeys.SEQ_RECOVER_GAP : 0;
        Long recovered = redis.execute(recoverAndIncr, List.of(seqKey), String.valueOf(base));
        return recovered != null ? recovered : 0L;
    }

    /** DuplicateKey 재시도 — seq 강제 점프. */
    public long forceJump(String roomId, int jitter) {
        Long v = redis.execute(forceJump, List.of(ChatRedisKeys.roomSeq(roomId)),
                String.valueOf(ChatRedisKeys.SEQ_FORCE_JUMP_GAP), String.valueOf(jitter));
        return v != null ? v : 0L;
    }

    /** rate limit — 원자적 INCR+EXPIRE. 이번 윈도우 누적 카운트 반환. */
    public long incrWithTtl(String key, long ttlSeconds) {
        Long v = redis.execute(incrWithTtl, List.of(key), String.valueOf(ttlSeconds));
        return v != null ? v : 0L;
    }
}
