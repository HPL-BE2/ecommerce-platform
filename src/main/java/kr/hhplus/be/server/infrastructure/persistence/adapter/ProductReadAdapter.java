package kr.hhplus.be.server.infrastructure.persistence.adapter;

import kr.hhplus.be.server.domain.model.Product;
import kr.hhplus.be.server.domain.port.out.ProductDetailReadPort;
import kr.hhplus.be.server.domain.port.out.ProductReadPort;
import kr.hhplus.be.server.infrastructure.persistence.entity.InventoryEntity;
import kr.hhplus.be.server.infrastructure.persistence.entity.ProductEntity;
import kr.hhplus.be.server.infrastructure.persistence.repo.SpringInventoryJpa;
import kr.hhplus.be.server.infrastructure.persistence.repo.SpringProductJpa;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ProductReadAdapter implements ProductReadPort, ProductDetailReadPort {
    private final SpringProductJpa productJpa;
    private final SpringInventoryJpa inventoryJpa;

    @Override
    public List<Product> fetchAfter(Long cursor, int limit, String q, Long categoryId, String sort) {
        List<ProductEntity> entities = (q != null || (sort != null && sort.equals("price")))
                ? productJpa.search(cursor, q, sort)
                : (cursor == null
                ? productJpa.findTop100ByIdGreaterThanOrderByIdAsc(0L)
                : productJpa.findTop100ByIdGreaterThanOrderByIdAsc(cursor));

        // limit 보정
        if (entities.size() > limit) entities = entities.subList(0, limit);

        return entities.stream().map(e ->
                new Product(e.getId(), e.getSku(), e.getName(), e.getPrice(), e.getStock(), e.getThumbnailUrl())
        ).toList();
    }

    @Override
    public Optional<Product> findById(Long productId) {
        return productJpa.findById(productId).map(entity -> {
            InventoryEntity inv = inventoryJpa.findById(entity.getId()).orElse(null);
            int stock = (inv != null ? inv.getStock() : 0);
            return new Product(
                    entity.getId(),
                    entity.getSku(),
                    entity.getName(),
                    entity.getPrice() != null ? entity.getPrice() : BigDecimal.ZERO,
                    stock,
                    entity.getThumbnailUrl()
            );
        });
    }
}
