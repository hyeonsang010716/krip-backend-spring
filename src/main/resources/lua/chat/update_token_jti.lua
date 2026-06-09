-- 살아있는 세션의 token_jti 만 갱신. 키 부재 시 no-op —
-- HSET 이 만료된 세션을 TTL 없는 좀비 해시로 부활시키는 것을 막는다.
-- KEYS[1] = sess:{sid}   ARGV[1] = new token_jti
-- return  = 갱신 1 / 부재 0
if redis.call('EXISTS', KEYS[1]) == 1 then
    redis.call('HSET', KEYS[1], 'token_jti', ARGV[1])
    return 1
end
return 0
