-- Fixed-window counter, atomic. INCR and EXPIRE run as one Redis command so
-- there is no gap where a crash or reschedule between the two leaves a key
-- with no TTL - a key that never expires would permanently lock out
-- whichever IP happened to hit it, which is worse than the rate limit it was
-- meant to enforce.
--
-- KEYS[1] = the per-IP, per-rule counter key
-- ARGV[1] = window length in seconds
--
-- Returns {count, ttl}. ttl is read back rather than assumed, so the caller
-- can report an accurate Retry-After even though this script only SETS the
-- TTL on the request that creates the key.
local count = redis.call('INCR', KEYS[1])
if count == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
local ttl = redis.call('TTL', KEYS[1])
return {count, ttl}
