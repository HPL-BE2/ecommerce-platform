package kr.hhplus.be.server.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

import java.time.OffsetDateTime;

@Entity
@Table(name="inventory")
@Getter
public class InventoryEntity {
    @Id
    @Column(name="product_id") private Long productId; // FK to products.id
    @Column(nullable=false) private Integer stock;
    @Column(name="safety_stock", nullable=false) private Integer safetyStock;
    @Column(nullable=false, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @Version
    private Long version;

    /**
     * 재고 감소 (동시성 제어: Optimistic Lock)
     * @param quantity 감소할 수량
     * @throws IllegalStateException 재고 부족 시
     */
    public void decreaseStock(int quantity) {
        if (this.stock < quantity) {
            throw new IllegalStateException("재고 부족: productId=" + productId + ", 현재=" + stock + ", 요청=" + quantity);
        }
        this.stock -= quantity;
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * 재고 증가 (반품, 취소 등)
     * @param quantity 증가할 수량
     */
    public void increaseStock(int quantity) {
        this.stock += quantity;
        this.updatedAt = OffsetDateTime.now();
    }
}
