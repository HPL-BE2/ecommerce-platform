package kr.hhplus.be.server.layered.application.port.in;

public interface GetProductDetailUseCase {
    record Query(Long productId) {}
    record Result(Long id, String sku, String name, int price, int stock,
                  String thumbnailUrl) {}
    Result get(Query query);
}
