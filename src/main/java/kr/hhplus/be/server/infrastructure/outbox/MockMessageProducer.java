package kr.hhplus.be.server.infrastructure.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class MockMessageProducer implements OutboundMessageProducer {

    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void send(String eventType, String payload) {
        log.info("[MockMessageProducer] eventType={} payload={} ", eventType, payload);
        eventPublisher.publishEvent(new OutboundMessagePublishedEvent(eventType, payload));
    }
}
