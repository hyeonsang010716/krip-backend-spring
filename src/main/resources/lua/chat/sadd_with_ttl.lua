-- SADD + EXPIRE 원자화. 분리하면 두 명령 사이 크래시(또는 EXPIRE 실패)로
-- TTL 없는 좀비 set 이 영구 잔존, 멤버/차단 캐시의 stale 상한(TTL)이 사라진다 — 반드시 Lua 로 묶는다.
-- KEYS[1] = set 키   ARGV[1] = TTL seconds   ARGV[2..] = 추가할 멤버(≥1)
redis.call('SADD', KEYS[1], unpack(ARGV, 2))
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
return 1
