package kr.hhplus.be.server.application.port.in;

public interface IssueCouponUseCase {
    record Command(Long couponId, Long userId) { }
    record Result(Long issuanceId, Long couponId, Long userId, String message) { }

    Result issue(Command command);
}
