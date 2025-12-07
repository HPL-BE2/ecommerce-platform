package kr.hhplus.be.server.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.hhplus.be.server.domain.port.out.dto.OrderCompletedEvent;
import kr.hhplus.be.server.infrastructure.persistence.entity.OutboxEventEntity;
import kr.hhplus.be.server.infrastructure.persistence.entity.OutboxEventStatus;
import kr.hhplus.be.server.infrastructure.persistence.repo.SpringOutboxEventJpa;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Outbox Kafka Dispatcher
 *
 * Outbox 테이블에서 PENDING 이벤트를 조회하여 Kafka로 발행
 * 스케줄러 기반으로 주기적으로 실행
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxKafkaDispatcher {

    private final SpringOutboxEventJpa outboxRepository;
    private final KafkaTemplate<Long, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${outbox.dispatcher.enabled:true}")
    private boolean enabled;

    @Value("${outbox.dispatcher.batch-size:20}")
    private int batchSize;

    @Value("${outbox.dispatcher.max-retry:5}")
    private int maxRetry;

    @Value("${outbox.dispatcher.retry-delay-seconds:30}")
    private long retryDelaySeconds;

    private static final String TOPIC = "ecommerce.order.events";

    @Scheduled(fixedDelayString = "${outbox.dispatcher.interval-ms:5000}")
    @Transactional
    public void dispatch() {
        if (!enabled) {
            return;
        }

        List<OutboxEventEntity> events = outboxRepository
                .findByStatusAndNextRetryAtLessThanEqualOrderByIdAsc(
                        OutboxEventStatus.PENDING,
                        OffsetDateTime.now(),
                        PageRequest.of(0, Math.max(1, batchSize))
                );

        if (events.isEmpty()) {
            return;
        }

        log.info("[Kafka] Outbox 이벤트 발행 시작: count={}", events.size());

        for (OutboxEventEntity event : events) {
            try {
                // JSON 파싱하여 orderId 추출 (Key로 사용)
                OrderCompletedEvent parsedEvent = objectMapper.readValue(
                        event.getPayload(),
                        OrderCompletedEvent.class
                );

                // Kafka 발행 (동기, 10초 타임아웃)
                kafkaTemplate.send(
                        TOPIC,
                        parsedEvent.orderId(),   // Key: orderId
                        event.getPayload()        // Value: JSON string
                ).get(10, TimeUnit.SECONDS);

                // 성공 시 상태 변경
                event.setStatus(OutboxEventStatus.SENT);
                event.setSentAt(OffsetDateTime.now());
                event.setLastError(null);

                log.info("[Kafka] 발행 성공: id={}, orderId={}", event.getId(), event.getAggregateId());

            } catch (Exception ex) {
                handleFailure(event, ex);
            }
        }
    }

    private void handleFailure(OutboxEventEntity event, Exception ex) {
        int nextRetry = event.getRetryCount() + 1;
        event.setRetryCount(nextRetry);
        event.setLastError(ex.getMessage());

        if (nextRetry >= maxRetry) {
            log.error("[Kafka] Outbox 이벤트 최대 재시도 초과: id={}, retries={}",
                    event.getId(), nextRetry, ex);
            event.setStatus(OutboxEventStatus.FAILED);
        } else {
            event.setStatus(OutboxEventStatus.PENDING);
            long delaySeconds = (long) Math.pow(2, nextRetry) * retryDelaySeconds;  // Exponential Backoff
            event.setNextRetryAt(OffsetDateTime.now().plusSeconds(delaySeconds));

            log.warn("[Kafka] Outbox 이벤트 재시도 예약: id={}, retryCount={}, nextRetry={}",
                    event.getId(), nextRetry, event.getNextRetryAt());
        }
    }
}
