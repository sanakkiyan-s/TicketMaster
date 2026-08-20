-- Atomic two-window INCR+EXPIRE (ADR-040). Both windows increment in one
-- round trip so a crash between them can never leave one window counted
-- and the other not - same reasoning as the single-window script this
-- replaces.
--
-- KEYS[1] = fast-window failure counter key
-- KEYS[2] = slow-window failure counter key
-- ARGV[1] = fast window TTL, seconds
-- ARGV[2] = slow window TTL, seconds
local fast_count = redis.call('INCR', KEYS[1])
if fast_count == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end

local slow_count = redis.call('INCR', KEYS[2])
if slow_count == 1 then
    redis.call('EXPIRE', KEYS[2], ARGV[2])
end

return {fast_count, slow_count}
