package kr.hhplus.be.server.infrastructure.config;

import kr.hhplus.be.server.infrastructure.persistence.repo.SpringCouponJpa;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 카운터 초기화
 * <p>
 * 애플리케이션 시작 시 DB의 쿠폰 발급 수량을 Redis에 동기화합니다.
 * </p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RedisCounterInitializer {

    private final SpringCouponJpa couponJpa;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 애플리케이션 준비 완료 후 Redis 카운터 초기화
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeCouponCounters() {
        log.info("[RedisCounterInitializer] 쿠폰 카운터 초기화 시작");

        var coupons = couponJpa.findAll();
        int initializedCount = 0;

        for (var coupon : coupons) {
            // 발급 제한이 있는 쿠폰만 초기화
            if (coupon.getMaxIssuance() != null && coupon.getMaxIssuance() > 0) {
                String countKey = "coupon:" + coupon.getId() + ":issued";
                Long issuedCount = Long.valueOf(coupon.getIssuedCount());

                // Redis에 현재 발급 수량 저장
                redisTemplate.opsForValue().set(countKey, issuedCount);

                log.debug("[RedisCounterInitializer] 쿠폰 카운터 초기화: couponId={}, issuedCount={}, maxIssuance={}",
                        coupon.getId(), issuedCount, coupon.getMaxIssuance());

                initializedCount++;
            }
        }

        log.info("[RedisCounterInitializer] 쿠폰 카운터 초기화 완료: {}개 쿠폰", initializedCount);
    }
}
