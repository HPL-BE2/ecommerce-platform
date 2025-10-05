package kr.hhplus.be.server.infrastructure.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.SimpleKeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {
    @Bean LettuceConnectionFactory redisConnectionFactory(){ return new LettuceConnectionFactory(); }
    @Bean CacheManager cacheManager(LettuceConnectionFactory cf){
        var conf = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(30))
//                .entryTtl(Duration.ofMinutes(5))
                .disableCachingNullValues()
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new GenericJackson2JsonRedisSerializer()));
        // 캐시별 설정 다르게 하기
        var productConf = conf.entryTtl(Duration.ofMinutes(3));

        return RedisCacheManager.builder(cf)
                .cacheDefaults(conf)
                .withCacheConfiguration("products", productConf)
                .build();
    }
}
