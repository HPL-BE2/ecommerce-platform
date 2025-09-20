package kr.hhplus.be.server.layered.interfaces.web.dto;

import java.util.List;

public class ProductDtos {
    public record ProductItemResponse(Long id, String sku, String name, Money price, Integer stock, String thumbnailUrl) {
        public record Money(int amount, String currency) {}
    }
    public record ProductListResponse(List<ProductItemResponse> items, Meta meta) {
        public record Meta(Long nextCursor) {}
    }
}
