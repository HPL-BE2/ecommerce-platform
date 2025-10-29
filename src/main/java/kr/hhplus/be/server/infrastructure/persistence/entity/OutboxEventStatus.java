package kr.hhplus.be.server.infrastructure.persistence.entity;

public enum OutboxEventStatus {
    PENDING,
    SENT,
    FAILED
}
