-- 원자적 INCR + EXPIRE. INCR 과 EXPIRE 를 분리하면 두 명령 사이 크래시 시
-- TTL 없는 키가 영구 잔존해 유저가 영구 차단되는 함정 — 반드시 Lua 로 묶는다.
-- KEYS[1] = 대상 키 (예: rate:msg:{user_id})
-- ARGV[1] = TTL seconds
-- return  = 이번 윈도우 누적 카운트 (int)
local cur = redis.call('INCR', KEYS[1])
if cur == 1 then
    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
end
return cur
