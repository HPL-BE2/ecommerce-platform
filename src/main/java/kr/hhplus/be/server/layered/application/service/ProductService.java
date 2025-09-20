package kr.hhplus.be.server.layered.application.service;

import kr.hhplus.be.server.layered.application.port.in.GetProductDetailUseCase;
import kr.hhplus.be.server.layered.application.port.in.ListProductsUseCase;
import kr.hhplus.be.server.layered.domain.model.Product;
import kr.hhplus.be.server.layered.domain.port.out.ProductDetailReadPort;
import kr.hhplus.be.server.layered.domain.port.out.ProductReadPort;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ProductService implements ListProductsUseCase, GetProductDetailUseCase {
    private final ProductReadPort productReadPort;
    private final ProductDetailReadPort detailPort;

    @Override
    @Cacheable(value = "products", key = "T(java.util.Objects).hash(#query.limit,#query.cursor,#query.q,#query.categoryId,#query.sort)")
    public ListProductsUseCase.Result list(ListProductsUseCase.Query query) {
        int limit = (query.limit() == null ? 20 : Math.max(1, Math.min(100, query.limit())));
        List<Product> products = productReadPort.fetchAfter(query.cursor(), limit, query.q(), query.categoryId(), query.sort());

        Long next = products.size() == limit ? products.get(products.size() - 1).id() : null;

        List<Item> items = products.stream()
                .map(p -> new Item(
                        p.id(), p.sku(), p.name(),
                        p.price().intValue(), p.stock(), p.thumbnailUrl()))
                .toList();

        return new ListProductsUseCase.Result(items, next);
    }

    @Override
    @Cacheable(value = "product-detail", key = "#query.productId")
    public GetProductDetailUseCase.Result get(GetProductDetailUseCase.Query query) {
        Product p = detailPort.findById(query.productId())
                .orElseThrow(() -> new IllegalArgumentException("상품 없음: id=" + query.productId()));

        return new GetProductDetailUseCase.Result(
                p.id(), p.sku(), p.name(),
                p.price().intValue(), p.stock(),
                p.thumbnailUrl()
        );
    }
}
