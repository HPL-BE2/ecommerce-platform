package kr.hhplus.be.server.interfaces.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.hhplus.be.server.application.port.in.GetProductDetailUseCase;
import kr.hhplus.be.server.application.port.in.ListProductsUseCase;
import kr.hhplus.be.server.interfaces.web.dto.ApiEnvelope;
import kr.hhplus.be.server.interfaces.web.dto.ProductDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/products")
@Validated @RequiredArgsConstructor
public class ProductsController {
    private final ListProductsUseCase listProducts;
    private final GetProductDetailUseCase getProductDetail;

    @GetMapping
    public ApiEnvelope<ProductDtos.ProductListResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "id") String sort,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) Integer limit,
            @RequestParam(required = false) Long cursor
    ) {
        var result = listProducts.list(new ListProductsUseCase.Query(limit, cursor, q, categoryId, sort));

        List<ProductDtos.ProductItemResponse> items = result.items().stream().map(i ->
                new ProductDtos.ProductItemResponse(
                        i.id(), i.sku(), i.name(),
                        new ProductDtos.ProductItemResponse.Money(i.price(), "KRW"),
                        i.stock(), i.thumbnailUrl()
                )
        ).toList();

        var body = new ProductDtos.ProductListResponse(items, new ProductDtos.ProductListResponse.Meta(result.nextCursor()));
        return new ApiEnvelope<>(body);
    }

    @GetMapping("/{productId}")
    public ApiEnvelope<ProductDtos.ProductItemResponse> detail(@PathVariable Long productId) {
        var result = getProductDetail.get(new GetProductDetailUseCase.Query(productId));
        return new ApiEnvelope<>(
                new ProductDtos.ProductItemResponse(
                        result.id(),
                        result.sku(),
                        result.name(),
                        new ProductDtos.ProductItemResponse.Money(result.price(), "KRW"),
                        result.stock(),
                        result.thumbnailUrl()
                )
        );
    }
}
