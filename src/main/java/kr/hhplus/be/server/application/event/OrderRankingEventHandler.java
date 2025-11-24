package kr.hhplus.be.server.application.event;

import kr.hhplus.be.server.application.service.ProductRankingUpdater;
import kr.hhplus.be.server.domain.event.OrderCompletedDomainEvent;
import kr.hhplus.be.server.domain.port.out.dto.OrderCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 주문 완료 이벤트 → 상품 랭킹 업데이트 핸들러
 *
 * 주문 트랜잭션 커밋 후 비동기로 Redis 랭킹 업데이트
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderRankingEventHandler {

    private final ProductRankingUpdater rankingUpdater;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(OrderCompletedDomainEvent domainEvent) {
        log.info("[Ranking] 주문 완료 이벤트 수신 orderId={}", domainEvent.orderId());

        try {
            // Domain Event → Ranking Event 변환
            OrderCompletedEvent rankingEvent = new OrderCompletedEvent(
                    domainEvent.orderId(),
                    domainEvent.userId(),
                    domainEvent.subtotal(),
                    domainEvent.discount(),
                    domainEvent.total(),
                    domainEvent.requestKey(),
                    domainEvent.completedAt(),
                    domainEvent.items().stream()
                            .map(item -> new OrderCompletedEvent.Item(
                                    item.productId(),
                                    item.name(),
                                    item.unitPrice(),
                                    item.qty(),
                                    item.lineTotal()
                            ))
                            .toList()
            );

            // 상품별 판매량 집계하여 Redis 랭킹 업데이트
            rankingUpdater.handle(rankingEvent);

            log.info("[Ranking] 랭킹 업데이트 완료 orderId={} items={}",
                    domainEvent.orderId(), domainEvent.items().size());

        } catch (Exception e) {
            log.error("[Ranking] 랭킹 업데이트 실패 orderId={} error={}",
                    domainEvent.orderId(), e.getMessage(), e);
        }
    }
}
