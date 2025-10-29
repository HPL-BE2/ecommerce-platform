package kr.hhplus.be.server.domain.port.out;

import kr.hhplus.be.server.domain.model.OrderModels;
import java.util.List;

public interface ProductPricePort {
    List<OrderModels.ProductPrice> loadPrices(List<Long> productIds);
}
