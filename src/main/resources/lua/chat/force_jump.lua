-- Mongo DuplicateKey 재시도 경로에서 seq 를 강제로 앞으로 점프.
-- 키가 증발한 엣지 케이스는 cur=0 으로 안전 처리.
-- KEYS[1] = room:seq:{room_id}
-- ARGV[1] = gap, ARGV[2] = jitter, ARGV[3] = ttl(seconds)
-- return  = 새로 세팅된 seq (int)
local cur = redis.call('GET', KEYS[1])
local cur_num = tonumber(cur) or 0
local new_val = cur_num + tonumber(ARGV[1]) + tonumber(ARGV[2])
redis.call('SET', KEYS[1], new_val)
redis.call('EXPIRE', KEYS[1], ARGV[3])
return new_val
