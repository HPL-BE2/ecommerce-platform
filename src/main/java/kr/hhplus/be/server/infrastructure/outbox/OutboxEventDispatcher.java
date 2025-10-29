package kr.hhplus.be.server.infrastructure.outbox;

import kr.hhplus.be.server.infrastructure.persistence.entity.OutboxEventEntity;
import kr.hhplus.be.server.infrastructure.persistence.entity.OutboxEventStatus;
import kr.hhplus.be.server.infrastructure.persistence.repo.SpringOutboxEventJpa;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxEventDispatcher {

    private final SpringOutboxEventJpa outboxRepository;
    private final OutboundMessageProducer messageProducer;

    @Value("${outbox.dispatcher.enabled:true}")
    private boolean enabled;

    @Value("${outbox.dispatcher.batch-size:20}")
    private int batchSize;

    @Value("${outbox.dispatcher.max-retry:5}")
    private int maxRetry;

    @Value("${outbox.dispatcher.retry-delay-seconds:30}")
    private long retryDelaySeconds;

    @Scheduled(fixedDelayString = "${outbox.dispatcher.interval-ms:5000}")
    @Transactional
    public void dispatch() {
        if (!enabled) {
            return;
        }

        var events = outboxRepository.findByStatusAndNextRetryAtLessThanEqualOrderByIdAsc(
                OutboxEventStatus.PENDING,
                OffsetDateTime.now(),
                PageRequest.of(0, Math.max(1, batchSize))
        );

        if (events.isEmpty()) {
            return;
        }

        for (var event : events) {
            try {
                messageProducer.send(event.getEventType(), event.getPayload());
                event.setStatus(OutboxEventStatus.SENT);
                event.setSentAt(OffsetDateTime.now());
                event.setLastError(null);
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
            log.error("Outbox 이벤트 처리 실패(최대 재시도 초과) id={} type={} error={}", event.getId(), event.getEventType(), ex.getMessage(), ex);
            event.setStatus(OutboxEventStatus.FAILED);
        } else {
            event.setStatus(OutboxEventStatus.PENDING);
            long delaySeconds = retryDelaySeconds * nextRetry;
            event.setNextRetryAt(OffsetDateTime.now().plus(Duration.ofSeconds(delaySeconds)));
            log.warn("Outbox 이벤트 처리 실패, 재시도 예약 id={} retryCount={} next={} error={}",
                    event.getId(), nextRetry, event.getNextRetryAt(), ex.getMessage());
        }
    }
}
