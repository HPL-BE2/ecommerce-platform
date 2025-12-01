package kr.hhplus.be.server.interfaces.web;

import kr.hhplus.be.server.application.port.in.IssueCouponUseCase;
import kr.hhplus.be.server.application.port.in.RequestCouponIssueUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/coupons")
@RequiredArgsConstructor
public class CouponController {
    private final IssueCouponUseCase issueCouponUseCase;
    private final RequestCouponIssueUseCase requestCouponIssueUseCase;

    /**
     * 쿠폰 발급 API
     * POST /coupons/{couponId}/issue
     *
     * @param couponId 쿠폰 ID
     * @param userId 사용자 ID (실제로는 @AuthenticationPrincipal로 받아야 함)
     * @return 발급 결과
     */
    @PostMapping("/{couponId}/issue")
    public ResponseEntity<IssueCouponUseCase.Result> issueCoupon(
            @PathVariable Long couponId,
            @RequestParam Long userId  // TODO: @AuthenticationPrincipal로 변경
    ) {
        var result = issueCouponUseCase.issue(
                new IssueCouponUseCase.Command(couponId, userId)
        );
        return ResponseEntity.ok(result);
    }

    /**
     * 쿠폰 발급 API (Kafka 비동기)
     * POST /coupons/{couponId}/issue-async
     *
     * Kafka 기반 비동기 처리:
     * 1. Lua Script로 Redis 검증
     * 2. Kafka로 발급 요청 발행
     * 3. 즉시 응답 202 Accepted
     *
     * @param couponId 쿠폰 ID
     * @param userId 사용자 ID
     * @return 발급 요청 접수 결과
     */
    @PostMapping("/{couponId}/issue-async")
    public ResponseEntity<RequestCouponIssueUseCase.Result> issueCouponAsync(
            @PathVariable Long couponId,
            @RequestParam Long userId
    ) {
        var result = requestCouponIssueUseCase.request(new RequestCouponIssueUseCase.Command(couponId, userId));
        return ResponseEntity.accepted().body(result);
    }
}
