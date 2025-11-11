package kr.hhplus.be.server.application.port.in;

public interface RequestCouponIssueUseCase {

    record Command(Long couponId, Long userId) { }

    record Result(String requestId, String status, String message) { }

    Result request(Command command);
}
