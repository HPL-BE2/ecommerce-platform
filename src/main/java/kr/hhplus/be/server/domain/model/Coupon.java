package kr.hhplus.be.server.domain.model;

import java.time.OffsetDateTime;

public record Coupon(
        Long id,
        String code,
        String type,
        Integer value,
        Integer minAmount,
        Integer maxDiscount,
        Integer maxIssuance,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt
) {
    public boolean isActive(OffsetDateTime now) {
        boolean afterStart = startsAt == null || startsAt.isBefore(now) || startsAt.isEqual(now);
        boolean beforeEnd = endsAt == null || endsAt.isAfter(now);
        return afterStart && beforeEnd;
    }

    public boolean hasIssuanceLimit() {
        return maxIssuance != null && maxIssuance > 0;
    }
}
