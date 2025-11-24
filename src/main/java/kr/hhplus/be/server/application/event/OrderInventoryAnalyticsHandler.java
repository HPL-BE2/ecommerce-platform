package kr.hhplus.be.server.application.event;

import kr.hhplus.be.server.domain.event.OrderCompletedDomainEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 주문 완료 이벤트 → 재고 분석 핸들러
 *
 * 주문 트랜잭션 커밋 후 비동기로 재고 트렌드 분석
 * - 재고 부족 알림
 * - 인기 상품 분석
 * - 재입고 추천 등
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderInventoryAnalyticsHandler {

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(OrderCompletedDomainEvent event) {
        log.info("[Analytics] 주문 완료 이벤트 수신 orderId={}", event.orderId());

        try {
            // 재고 분석 로직 (향후 구현)
            // 1. 재고 임계값 확인
            // 2. 재입고 알림 발송
            // 3. 판매 트렌드 분석

            log.debug("[Analytics] 재고 분석 완료 orderId={} items={}",
                    event.orderId(), event.items().size());

        } catch (Exception e) {
            log.error("[Analytics] 재고 분석 실패 orderId={} error={}",
                    event.orderId(), e.getMessage(), e);
        }
    }
}
