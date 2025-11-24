package kr.hhplus.be.server.application.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.hhplus.be.server.domain.event.OrderCompletedDomainEvent;
import kr.hhplus.be.server.domain.port.out.dto.OrderCompletedEvent;
import kr.hhplus.be.server.infrastructure.outbox.OutboxOrderEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 주문 완료 이벤트 → 데이터 플랫폼 전송 핸들러
 *
 * 주문 트랜잭션 커밋 후 비동기로 Outbox에 저장
 * 실패해도 주문 트랜잭션에 영향 없음
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderDataPlatformEventHandler {

    private final OutboxOrderEventPublisher outboxPublisher;
    private final ObjectMapper objectMapper;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(OrderCompletedDomainEvent domainEvent) {
        log.info("[DataPlatform] 주문 완료 이벤트 수신 orderId={}", domainEvent.orderId());

        try {
            // Domain Event → Outbox Event 변환
            OrderCompletedEvent outboxEvent = new OrderCompletedEvent(
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

            // Outbox에 저장 (별도 트랜잭션)
            outboxPublisher.publish(outboxEvent);

            log.info("[DataPlatform] Outbox 저장 완료 orderId={}", domainEvent.orderId());

        } catch (Exception e) {
            // 실패해도 주문은 이미 완료된 상태
            log.error("[DataPlatform] Outbox 저장 실패 orderId={} error={}",
                    domainEvent.orderId(), e.getMessage(), e);
        }
    }
}
