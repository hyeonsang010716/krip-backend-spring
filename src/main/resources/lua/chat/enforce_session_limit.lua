-- 유저 세션 한도 강제 — 만료분 청소 + 초과분 oldest evict 를 원자 실행.
-- read-modify-write 를 한 스크립트로 묶어, 동시 접속이 stale count 로 살아있는 세션을 잘못 evict 하는
-- (over-eviction) 레이스를 제거한다. sess:/ws_route: 접두사는 ChatRedisKeys 와 일치해야 한다(단일 Redis 전제).
-- KEYS[1] = sessions:{user_id} ZSET (score = 만료시각 ms)
-- ARGV[1] = now(ms), ARGV[2] = limit
-- return  = evict 된 session_id 목록 (초과 없으면 빈 배열)
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
local over = redis.call('ZCARD', KEYS[1]) - tonumber(ARGV[2])
local evicted = {}
if over > 0 then
    local victims = redis.call('ZRANGE', KEYS[1], 0, over - 1)
    for _, sid in ipairs(victims) do
        redis.call('ZREM', KEYS[1], sid)
        redis.call('DEL', 'sess:' .. sid)
        redis.call('DEL', 'ws_route:' .. sid)
        evicted[#evicted + 1] = sid
    end
end
return evicted
