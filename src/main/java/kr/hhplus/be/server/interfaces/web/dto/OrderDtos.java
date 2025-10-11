package kr.hhplus.be.server.interfaces.web.dto;

import java.util.List;

public class OrderDtos {
    public record CreateOrderItem(Long productId, Integer qty) {}
    public record CreateOrderRequest(Long userId, List<CreateOrderItem> items,
                                     String couponCode, Integer expectedTotal) {}
    public record CreateOrderResponse(Long orderId, String status, Breakdown subtotal, Breakdown discount, Breakdown total) {
        public record Breakdown(int amount, String currency) {}
    }
    public record CompleteOrderResponse(Long orderId, int total) {}
}
