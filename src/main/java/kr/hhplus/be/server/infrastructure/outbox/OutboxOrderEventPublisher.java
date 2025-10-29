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

import java.time.OffsetDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxOrderEventPublisher implements OrderEventPublisher {

    private final SpringOutboxEventJpa outboxRepository;
    private final ObjectMapper objectMapper;

    @Override
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
        } catch (JsonProcessingException e) {
            log.error("OrderCompletedEvent 직렬화 실패 - orderId={}", event.orderId(), e);
            throw new IllegalStateException("이벤트 직렬화에 실패했습니다.", e);
        }
    }
}
