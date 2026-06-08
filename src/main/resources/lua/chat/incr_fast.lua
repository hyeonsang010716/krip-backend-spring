-- 방 시퀀스 fast-path 채번.
-- 키가 살아있으면 INCR 후 TTL 갱신(sliding) — 활성 방은 만료되지 않고 유휴 방만 회수된다.
-- Redis 가 리셋되어 키가 증발한 상태면 -1 을 돌려 호출측이 Mongo 조회 후 recover_and_incr.lua 로 넘어가게 한다.
-- KEYS[1] = room:seq:{room_id}
-- ARGV[1] = ttl(seconds)
-- return  = 다음 seq (int) | -1 (복구 경로 필요)
if redis.call('EXISTS', KEYS[1]) == 1 then
    local v = redis.call('INCR', KEYS[1])
    redis.call('EXPIRE', KEYS[1], ARGV[1])
    return v
end
return -1
