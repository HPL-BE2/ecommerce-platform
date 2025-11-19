package kr.hhplus.be.server.application.service;

import kr.hhplus.be.server.application.port.in.ReleaseInventoryUseCase;
import kr.hhplus.be.server.domain.model.OrderModels;
import kr.hhplus.be.server.domain.port.out.InventoryReservePort;
import kr.hhplus.be.server.infrastructure.lock.DistributedLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;

/**
 * 재고 관리 서비스
 * <p>
 * 분산락을 사용하여 재고 예약 동시성을 제어합니다.
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryService implements ReleaseInventoryUseCase {

    private final InventoryReservePort inventoryPort;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 재고 조회 (캐시 우선)
     * <p>
     * Cache-Aside 패턴으로 Redis에서 먼저 조회합니다.
     * 실제 주문 시에는 항상 DB 조회를 사용해야 합니다 (이 메서드는 UI 표시용).
     * </p>
     *
     * @param productId 상품 ID
     * @return 재고 수량 (캐시 또는 DB)
     */
    public Integer getStockForDisplay(Long productId) {
        String stockKey = "product:" + productId + ":stock";

        // Cache Hit
        Integer cached = (Integer) redisTemplate.opsForValue().get(stockKey);
        if (cached != null) {
            log.debug("[InventoryService] 재고 캐시 Hit: productId={}, stock={}", productId, cached);
            return cached;
        }

        // Cache Miss: DB 조회 후 캐시 저장
        List<OrderModels.Inventory> inventories = inventoryPort.lockInventories(List.of(productId));
        if (inventories.isEmpty()) {
            log.warn("[InventoryService] 재고 없음: productId={}", productId);
            return 0;
        }

        Integer dbStock = inventories.get(0).stock();
        redisTemplate.opsForValue().set(stockKey, dbStock, Duration.ofSeconds(10));
        log.debug("[InventoryService] 재고 캐시 Miss -> DB 조회 및 저장: productId={}, stock={}", productId, dbStock);

        return dbStock;
    }

    /**
     * 재고 예약 (분산락 적용)
     * <p>
     * 상품별로 분산락을 획득하여 동시 예약을 제어합니다.
     * Redis 재고 캐시를 먼저 확인하여 빠른 실패를 지원합니다.
     * </p>
     *
     * @param productId 상품 ID
     * @param quantity  예약 수량
     * @param orderId   주문 ID (null 가능)
     */
    @DistributedLock(
            key = "'product:' + #productId + ':order:lock'",
            leaseTime = 10000,
            waitTime = 2000,
            failMessage = "상품 재고 예약 요청이 집중되어 처리할 수 없습니다. 잠시 후 다시 시도해주세요."
    )
    @Transactional
    public void reserveWithLock(Long productId, int quantity, Long orderId) {
        // 1) Redis 캐시 재고 확인 (빠른 실패)
        String stockKey = "product:" + productId + ":stock";
        Integer cachedStock = (Integer) redisTemplate.opsForValue().get(stockKey);

        if (cachedStock != null && cachedStock < quantity) {
            log.warn("[InventoryService] 재고 부족 (캐시): productId={}, requested={}, cached={}",
                    productId, quantity, cachedStock);
            throw new IllegalStateException("재고가 부족합니다: productId=" + productId);
        }

        // 2) DB 재고 차감 (Optimistic Lock은 안전장치로 유지)
        try {
            inventoryPort.reserve(productId, quantity, orderId);
            log.info("[InventoryService] 재고 예약 성공: productId={}, quantity={}, orderId={}",
                    productId, quantity, orderId);

        } catch (Exception e) {
            log.error("[InventoryService] 재고 예약 실패: productId={}, quantity={}", productId, quantity, e);
            throw e;
        }

        // 3) Redis 캐시 재고 동기화 (트랜잭션 커밋 후 실행)
        if (cachedStock != null) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            Long newStock = redisTemplate.opsForValue().decrement(stockKey, quantity);
                            log.debug("[InventoryService] 재고 캐시 동기화 (트랜잭션 커밋 후): productId={}, newStock={}",
                                    productId, newStock);
                        } catch (Exception e) {
                            // 캐시 갱신 실패는 치명적이지 않음 (TTL로 자동 동기화)
                            log.warn("[InventoryService] 재고 캐시 갱신 실패 (TTL로 복구 예정): productId={}",
                                    productId, e);
                        }
                    }
                }
            );
        }
    }

    /**
     * 주문 취소 시 재고 복원
     * <p>
     * DB와 Redis 캐시 모두 복원합니다.
     * </p>
     *
     * @param productId 상품 ID
     * @param quantity  복원 수량
     */
    @Transactional
    public void restoreStock(Long productId, int quantity) {
        // TODO: DB 재고 복원 로직 구현 필요
        // inventoryPort.restore(productId, quantity);

        // Redis 캐시 복원
        String stockKey = "product:" + productId + ":stock";
        Long newStock = redisTemplate.opsForValue().increment(stockKey, quantity);
        log.info("[InventoryService] 재고 복원: productId={}, quantity={}, newStock={}", productId, quantity, newStock);
    }

    /**
     * 재고 캐시 초기화
     * <p>
     * DB의 재고 수량을 Redis에 저장합니다.
     * </p>
     *
     * @param productId 상품 ID
     * @param stock     재고 수량
     */
    public void initializeStockCache(Long productId, Integer stock) {
        String stockKey = "product:" + productId + ":stock";
        redisTemplate.opsForValue().set(stockKey, stock, Duration.ofSeconds(30));
        log.debug("[InventoryService] 재고 캐시 초기화: productId={}, stock={}", productId, stock);
    }

    /**
     * 재고 해제 (보상 트랜잭션)
     *
     * Saga 패턴에서 주문 생성 실패 시 이미 예약된 재고를 복원
     * 1. DB 재고 복원
     * 2. Redis 캐시 재고 증가
     */
    @Override
    @Transactional
    public ReleaseInventoryUseCase.Result release(ReleaseInventoryUseCase.Command cmd) {
        if (cmd.items() == null || cmd.items().isEmpty()) {
            throw new IllegalArgumentException("items는 필수입니다.");
        }

        log.info("[InventoryService] 재고 해제 시작: items={}, reason={}", cmd.items().size(), cmd.reason());

        int releasedCount = 0;

        for (var item : cmd.items()) {
            try {
                // TODO: DB 재고 복원 로직 구현 필요
                // inventoryPort.restore(item.productId(), item.quantity());

                // Redis 캐시 복원
                String stockKey = "product:" + item.productId() + ":stock";
                Long newStock = redisTemplate.opsForValue().increment(stockKey, item.quantity());

                log.info("[InventoryService] 재고 해제: productId={}, quantity={}, newStock={}",
                        item.productId(), item.quantity(), newStock);

                releasedCount++;

            } catch (Exception e) {
                log.error("[InventoryService] 재고 해제 실패: productId={}, quantity={}, error={}",
                        item.productId(), item.quantity(), e.getMessage(), e);
            }
        }

        if (releasedCount == 0) {
            return new ReleaseInventoryUseCase.Result(false, "재고 해제 실패", 0);
        }

        return new ReleaseInventoryUseCase.Result(
                true,
                releasedCount + "개 상품의 재고가 해제되었습니다.",
                releasedCount
        );
    }
}
