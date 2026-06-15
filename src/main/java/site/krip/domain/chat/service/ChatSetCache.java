package site.krip.domain.chat.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * set 캐시(roomMembers/roomBlocks) 쓰기.
 *
 * <p>SADD 와 EXPIRE 를 분리하면 그 사이 크래시(또는 EXPIRE 실패)로 TTL 없는 좀비 set 이 영구 잔존,
 * 캐시의 stale 상한(TTL)이 사라진다 — Lua 로 묶어 원자화한다.
 */
@Component
public class ChatSetCache {

    private final StringRedisTemplate redis;
    private final RedisScript<Long> saddWithTtl;

    public ChatSetCache(StringRedisTemplate redis,
                        @Qualifier("saddWithTtlScript") RedisScript<Long> saddWithTtl) {
        this.redis = redis;
        this.saddWithTtl = saddWithTtl;
    }

    /** members 를 key 에 SADD 하고 ttl 초로 EXPIRE — 원자적. 빈 리스트는 no-op(올릴 게 없음). */
    public void saddWithTtl(String key, long ttlSeconds, List<String> members) {
        if (members.isEmpty()) {
            // 추가할 멤버 없음 → no-op. 빈 채로 넘기면 스크립트의 SADD 가 0-인자 에러로 abort 된다.
            return;
        }
        List<String> args = new ArrayList<>(members.size() + 1);
        args.add(String.valueOf(ttlSeconds));
        args.addAll(members);
        redis.execute(saddWithTtl, List.of(key), args.toArray());
    }
}
