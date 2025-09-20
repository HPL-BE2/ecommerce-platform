package kr.hhplus.be.server.application.port.in;

import java.util.List;

public interface CreateOrderUseCase {
    record Item(Long productId, Integer qty) {}
    record Command(Long userId, List<Item> items, String couponCode, Integer expectedTotal, String requestKey) {}
    record Result(Long orderId, String status, int subtotal, int discount, int total) {}
    Result create(Command cmd);
}
