package kr.hhplus.be.server.interfaces.web.dto;

import java.util.List;

public class ProductDtos {
    public record ProductItemResponse(Long id, String sku, String name, Price price, Integer stock, String thumbnailUrl) {
        public record Price(int amount, String currency) {}
    }
    public record ProductListResponse(List<ProductItemResponse> items, Meta meta) {
        public record Meta(Long nextCursor) {}
    }
}
