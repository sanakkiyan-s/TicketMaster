-- Atomic INCR+EXPIRE. Two separate Java calls here (INCR then EXPIRE)
-- would leave a gap where a crash or reschedule between them leaves a key
-- with no TTL - permanently locking out whoever tripped it, worse than
-- the rate limit this exists to enforce. Same reasoning, same pattern,
-- as api-gateway's rate_limit.lua.
--
-- KEYS[1] = the per-username failure counter key
-- ARGV[1] = window length in seconds
local count = redis.call('INCR', KEYS[1])
if count == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
return count
