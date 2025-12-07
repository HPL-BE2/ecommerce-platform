package kr.hhplus.be.server.infrastructure.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.hhplus.be.server.domain.port.out.OrderEventPublisher;
import kr.hhplus.be.server.domain.port.out.dto.OrderCompletedEvent;
import kr.hhplus.be.server.infrastructure.persistence.entity.OutboxEventEntity;
import kr.hhplus.be.server.infrastructure.persistence.entity.OutboxEventStatus;
import kr.hhplus.be.server.infrastructure.persistence.repo.SpringOutboxEventJpa;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Kafka Outbox Producer
 *
 * 주문 완료 이벤트를 Outbox 테이블에 저장
 * Outbox Dispatcher가 Kafka로 발행
 */
@Component("outboxKafkaProducer")
@RequiredArgsConstructor
@Slf4j
public class OutboxKafkaProducer implements OrderEventPublisher {

    private final SpringOutboxEventJpa outboxRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publish(OrderCompletedEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);

            OutboxEventEntity entity = new OutboxEventEntity();
            entity.setAggregateType("order");
            entity.setAggregateId(String.valueOf(event.orderId()));
            entity.setEventType("ORDER_COMPLETED");
            entity.setPayload(payload);
            entity.setStatus(OutboxEventStatus.PENDING);
            entity.setRetryCount(0);
            entity.setNextRetryAt(OffsetDateTime.now());

            outboxRepository.save(entity);

            log.debug("[Kafka] Outbox 이벤트 저장: orderId={}", event.orderId());

        } catch (JsonProcessingException e) {
            log.error("[Kafka] 이벤트 직렬화 실패: orderId={}", event.orderId(), e);
            throw new IllegalStateException("이벤트 직렬화 실패", e);
        }
    }
}
