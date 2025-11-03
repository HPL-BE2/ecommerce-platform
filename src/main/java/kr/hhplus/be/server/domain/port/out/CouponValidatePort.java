package kr.hhplus.be.server.domain.port.out;

import kr.hhplus.be.server.domain.model.OrderModels;
import java.util.Optional;

public interface CouponValidatePort {
    Optional<OrderModels.CouponInfo> findApplicable(Long userId, String couponCode, int subtotal);
}
