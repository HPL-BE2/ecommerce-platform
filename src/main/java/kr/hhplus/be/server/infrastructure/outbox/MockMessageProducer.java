package kr.hhplus.be.server.infrastructure.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class MockMessageProducer implements OutboundMessageProducer {

    @Override
    public void send(String eventType, String payload) {
        log.info("[MockMessageProducer] eventType={} payload={} ", eventType, payload);
    }
}
