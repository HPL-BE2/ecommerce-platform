package kr.hhplus.be.server.infrastructure.coupon;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
@Slf4j
public class CouponIssueLuaScriptExecutor {

    private static final String LUA = """
            local remainingKey = KEYS[1]
            local userSetKey = KEYS[2]
            local userId = ARGV[1]

            local remaining = tonumber(redis.call('GET', remainingKey) or '-1')
            if remaining <= 0 then
                return 0
            end

            if redis.call('SISMEMBER', userSetKey, userId) == 1 then
                return 2
            end

            local after = redis.call('DECR', remainingKey)
            if after < 0 then
                redis.call('INCR', remainingKey)
                return 0
            end

            redis.call('SADD', userSetKey, userId)
            return 1
            """;

    private final RedisTemplate<String, String> counterRedisTemplate;
    private final DefaultRedisScript<Long> script;

    public CouponIssueLuaScriptExecutor(RedisTemplate<String, String> counterRedisTemplate) {
        this.counterRedisTemplate = counterRedisTemplate;
        this.script = new DefaultRedisScript<>();
        this.script.setScriptText(LUA);
        this.script.setResultType(Long.class);
    }

    public Decision tryAcquire(Long couponId, Long userId) {
        List<String> keys = List.of(
                CouponRedisKeys.remainingCount(couponId),
                CouponRedisKeys.issuedUsers(couponId)
        );

        Long result = counterRedisTemplate.execute(script, keys, String.valueOf(userId));
        if (result == null) {
            return Decision.ERROR;
        }
        return Decision.fromCode(result.intValue());
    }

    @Getter
    public enum Decision {
        RESERVED(1),
        SOLD_OUT(0),
        DUPLICATE(2),
        ERROR(-1);

        private final int code;

        Decision(int code) {
            this.code = code;
        }

        public static Decision fromCode(int code) {
            for (Decision value : values()) {
                if (value.code == code) return value;
            }
            return ERROR;
        }
    }
}
