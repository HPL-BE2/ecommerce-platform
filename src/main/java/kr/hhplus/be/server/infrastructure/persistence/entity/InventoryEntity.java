package kr.hhplus.be.server.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
}
