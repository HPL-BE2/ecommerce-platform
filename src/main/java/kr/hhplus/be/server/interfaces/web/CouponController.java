package kr.hhplus.be.server.interfaces.web;

import kr.hhplus.be.server.application.port.in.IssueCouponUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/coupons")
@RequiredArgsConstructor
public class CouponController {
    private final IssueCouponUseCase issueCouponUseCase;

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
}
