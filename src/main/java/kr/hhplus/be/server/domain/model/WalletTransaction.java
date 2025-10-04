package kr.hhplus.be.server.domain.model;

import java.time.OffsetDateTime;

public record WalletTransaction(
        Long id, Long userId, String type, long amount,
        long balanceAfter, String refType, String refId,
        String idempotencyKey, OffsetDateTime createdAt
) {}

