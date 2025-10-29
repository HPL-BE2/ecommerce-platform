package kr.hhplus.be.server.domain.port.out;

import kr.hhplus.be.server.domain.model.OrderModels;
import java.util.List;

public interface InventoryReservePort {
    List<OrderModels.Inventory> lockInventories(List<Long> productIds);
    void reserve(Long productId, int qty, Long orderId);
}
