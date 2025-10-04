package kr.hhplus.be.server.interfaces.web.dto;

import java.util.List;

public class OrderDtos {
    public record CreateOrderItem(Long productId, Integer qty) {}
    public record CreateOrderRequest(Long userId, List<CreateOrderItem> items,
                                     String couponCode, Integer expectedTotal) {}
    public record CreateOrderResponse(Long orderId, String status, Money subtotal, Money discount, Money total) {
        public record Money(int amount, String currency) {}
    }
}
