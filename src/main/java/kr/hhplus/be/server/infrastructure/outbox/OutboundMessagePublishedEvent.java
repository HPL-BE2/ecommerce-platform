package kr.hhplus.be.server.infrastructure.outbox;

public record OutboundMessagePublishedEvent(String eventType, String payload) { }
