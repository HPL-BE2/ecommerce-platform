package kr.hhplus.be.server.infrastructure.config;

import kr.hhplus.be.server.infrastructure.persistence.repo.SpringCouponJpa;
import kr.hhplus.be.server.infrastructure.persistence.repo.SpringInventoryJpa;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 카운터 및 캐시 초기화
 * <p>
 * 애플리케이션 시작 시 DB의 데이터를 Redis에 동기화합니다.
 * - 쿠폰 발급 수량 (Atomic Counter)
 * - 재고 수량 (Cache)
 * </p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RedisCounterInitializer {

    private final SpringCouponJpa couponJpa;
    private final SpringInventoryJpa inventoryJpa;
    private final RedisTemplate<String, String> counterRedisTemplate;  // 카운터 전용
    private final RedisTemplate<String, Object> redisTemplate;  // 캐시용

    /**
     * 애플리케이션 준비 완료 후 Redis 초기화
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeRedisData() {
        initializeCouponCounters();
        initializeInventoryCaches();
    }

    /**
     * 쿠폰 발급 카운터 초기화
     */
    private void initializeCouponCounters() {
        log.info("[RedisCounterInitializer] 쿠폰 카운터 초기화 시작");

        var coupons = couponJpa.findAll();
        int initializedCount = 0;

        for (var coupon : coupons) {
            // 발급 제한이 있는 쿠폰만 초기화
            if (coupon.getMaxIssuance() != null && coupon.getMaxIssuance() > 0) {
                String countKey = "coupon:" + coupon.getId() + ":issued";
                Long issuedCount = Long.valueOf(coupon.getIssuedCount());

                // Redis에 현재 발급 수량 저장 (String으로 저장하여 INCR/DECR 지원)
                counterRedisTemplate.opsForValue().set(countKey, String.valueOf(issuedCount));

                log.debug("[RedisCounterInitializer] 쿠폰 카운터 초기화: couponId={}, issuedCount={}, maxIssuance={}",
                        coupon.getId(), issuedCount, coupon.getMaxIssuance());

                initializedCount++;
            }
        }

        log.info("[RedisCounterInitializer] 쿠폰 카운터 초기화 완료: {}개 쿠폰", initializedCount);
    }

    /**
     * 재고 캐시 초기화
     */
    private void initializeInventoryCaches() {
        log.info("[RedisCounterInitializer] 재고 캐시 초기화 시작");

        var inventories = inventoryJpa.findAll();
        int initializedCount = 0;

        for (var inventory : inventories) {
            String stockKey = "product:" + inventory.getProductId() + ":stock";

            // Redis에 현재 재고 저장 (30초 TTL)
            redisTemplate.opsForValue().set(stockKey, inventory.getStock(), Duration.ofSeconds(30));

            log.debug("[RedisCounterInitializer] 재고 캐시 초기화: productId={}, stock={}",
                    inventory.getProductId(), inventory.getStock());

            initializedCount++;
        }

        log.info("[RedisCounterInitializer] 재고 캐시 초기화 완료: {}개 상품", initializedCount);
    }
}
