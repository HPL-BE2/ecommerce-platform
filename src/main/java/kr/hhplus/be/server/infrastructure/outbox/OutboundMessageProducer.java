package kr.hhplus.be.server.infrastructure.outbox;

public interface OutboundMessageProducer {
    void send(String eventType, String payload) throws Exception;
}
