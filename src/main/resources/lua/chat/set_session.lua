-- WS 세션 3키 원자 세팅. HSET 과 EXPIRE 를 분리하면 그 사이 크래시 시
-- TTL 없는 sess 해시가 영구 잔존하는 누수가 생긴다 — 반드시 Lua 로 묶는다.
-- KEYS[1] = sess:{sid} HASH   KEYS[2] = sessions:{uid} ZSET   KEYS[3] = ws_route:{sid} STRING
-- ARGV[1] = sid(ZSET member)  ARGV[2] = user_id  ARGV[3] = node_id  ARGV[4] = token_jti
-- ARGV[5] = connected_at(ms)  ARGV[6] = ttl(s)   ARGV[7] = expires_at(ms; ZSET score)
redis.call('HSET', KEYS[1], 'user_id', ARGV[2], 'node_id', ARGV[3], 'token_jti', ARGV[4], 'connected_at', ARGV[5])
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[6]))
redis.call('ZADD', KEYS[2], ARGV[7], ARGV[1])
redis.call('SET', KEYS[3], ARGV[3], 'EX', tonumber(ARGV[6]))
return 1
