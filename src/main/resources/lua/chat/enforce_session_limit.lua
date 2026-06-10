-- 유저 세션 한도 강제 — 만료분 청소 + 초과분 oldest evict 를 원자 실행.
-- read-modify-write 를 한 스크립트로 묶어, 동시 접속이 stale count 로 살아있는 세션을 잘못 evict 하는
-- (over-eviction) 레이스를 제거한다. sess:/ws_route: 접두사는 ChatRedisKeys 와 일치해야 한다(단일 Redis 전제).
-- KEYS[1] = sessions:{user_id} ZSET (score = 만료시각 ms)
-- ARGV[1] = now(ms), ARGV[2] = limit, ARGV[3] = 방금 만든 세션 id(보호 대상; 없으면 빈 문자열)
-- return  = evict 된 session_id 목록 (초과 없으면 빈 배열)
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
local over = redis.call('ZCARD', KEYS[1]) - tonumber(ARGV[2])
local evicted = {}
if over > 0 then
    local protected = ARGV[3]
    -- 동일 score(같은 ms 생성) tie 에서 갓 만든 세션이 최저 정렬로 victim 에 끼는 걸 막는다.
    -- 후보를 하나 더(0..over) 뽑아 protected 를 건너뛰고 정확히 over 개만 evict.
    local victims = redis.call('ZRANGE', KEYS[1], 0, over)
    local n = 0
    for _, sid in ipairs(victims) do
        if n >= over then break end
        if sid ~= protected then
            redis.call('ZREM', KEYS[1], sid)
            redis.call('DEL', 'sess:' .. sid)
            redis.call('DEL', 'ws_route:' .. sid)
            evicted[#evicted + 1] = sid
            n = n + 1
        end
    end
end
return evicted
