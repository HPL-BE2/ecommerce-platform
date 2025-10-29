package kr.hhplus.be.server.domain.port.out;

import kr.hhplus.be.server.domain.model.OrderModels;
import java.util.List;
import java.util.Optional;

public interface OrderWritePort {
    Optional<Long> findOrderIdByRequestKey(Long userId, String requestKey);
    Long createReservedOrder(Long userId, List<OrderModels.OrderItem> items, int subtotal, int discount, int total, String requestKey, Long couponIssuanceId);
    OrderModels.OrderSummary markOrderCompleted(Long orderId);
}
