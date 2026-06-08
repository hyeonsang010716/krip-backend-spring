-- Redis 리셋 이후 첫 채번 복구.
-- 키가 없거나 현재값이 base(=mongo_max + safety_gap) 보다 작은 경우에만 SET —
-- 다른 프로세스가 이미 앞서 나가 있는 상태를 덮지 않도록 cur < base 가드.
-- KEYS[1] = room:seq:{room_id}
-- ARGV[1] = base, ARGV[2] = ttl(seconds)
-- return  = 다음 seq (int)
local cur = redis.call('GET', KEYS[1])
local base = tonumber(ARGV[1])
if (not cur) or (tonumber(cur) < base) then
    redis.call('SET', KEYS[1], base)
end
local v = redis.call('INCR', KEYS[1])
redis.call('EXPIRE', KEYS[1], ARGV[2])
return v
