package kr.hhplus.be.server.application.port.in;

import java.util.List;

public interface ListProductsUseCase {
    record Query(Integer limit, Long cursor, String q, Long categoryId, String sort) {}
    record Item(Long id, String sku, String name, int price, int stock, String thumbnailUrl) {}
    record Result(List<Item> items, Long nextCursor) {}
    Result list(Query query);
}
