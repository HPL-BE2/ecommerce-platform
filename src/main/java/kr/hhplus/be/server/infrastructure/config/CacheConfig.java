package kr.hhplus.be.server.infrastructure.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Bean
    LettuceConnectionFactory redisConnectionFactory() {
        return new LettuceConnectionFactory(redisHost, redisPort);
    }

    @Bean
    CacheManager cacheManager(LettuceConnectionFactory cf) {
        var conf = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(30))
                .disableCachingNullValues()
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new GenericJackson2JsonRedisSerializer()));

        // 캐시별 설정
        var productConf = conf.entryTtl(Duration.ofMinutes(3));
        var couponInfoConf = conf.entryTtl(Duration.ofMinutes(5));
        var activeCouponsConf = conf.entryTtl(Duration.ofMinutes(5));
        var userOrdersConf = conf.entryTtl(Duration.ofMinutes(10));
        var orderDetailConf = conf.entryTtl(Duration.ofMinutes(10));

        return RedisCacheManager.builder(cf)
                .cacheDefaults(conf)
                .withCacheConfiguration("products", productConf)
                .withCacheConfiguration("coupon-info", couponInfoConf)
                .withCacheConfiguration("active-coupons", activeCouponsConf)
                .withCacheConfiguration("user-orders", userOrdersConf)
                .withCacheConfiguration("order-detail", orderDetailConf)
                .build();
    }

    /**
     * RedisTemplate for manual Redis operations (e.g., caching objects)
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(LettuceConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }

    /**
     * RedisTemplate for atomic counters (INCR/DECR operations)
     * <p>
     * Uses StringRedisSerializer for both key and value to support Redis atomic operations.
     * GenericJackson2JsonRedisSerializer would store numbers as JSON objects,
     * which prevents INCR/DECR commands from working.
     * </p>
     */
    @Bean
    public RedisTemplate<String, String> counterRedisTemplate(LettuceConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());  // String으로 숫자 저장
        return template;
    }

    /**
     * Redisson Client for distributed locks
     * <p>
     * Connection Pool 설정:
     * - ConnectionPoolSize: 20 (DB 커넥션 풀의 약 5~10배 권장)
     * - MinimumIdleSize: 5 (유휴 커넥션 최소 유지)
     * <p>
     * Redisson은 Netty 기반 비동기 처리이지만, 과도한 풀 크기는 오히려
     * Redis 서버 부하와 메모리 사용량을 증가시킵니다.
     * </p>
     */
    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + redisPort)
                .setConnectionPoolSize(20)          // 50 → 20 (DB 커넥션 풀 대비 적정 수준)
                .setConnectionMinimumIdleSize(5)     // 10 → 5 (최소 유휴 커넥션 감소)
                .setConnectTimeout(3000)
                .setTimeout(3000)
                .setRetryAttempts(3)
                .setRetryInterval(1500);

        return Redisson.create(config);
    }
}
