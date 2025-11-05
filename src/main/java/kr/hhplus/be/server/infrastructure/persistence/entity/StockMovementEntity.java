package kr.hhplus.be.server.infrastructure.persistence.entity;


import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name="stock_movements")
@Getter
@Setter
@NoArgsConstructor
public class StockMovementEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="product_id", nullable=false) private Long productId;
    @Column(name="qty", nullable=false) private Integer qty;
    @Column(name="reason", nullable=false, length=32) private String reason; // RESERVE
    @Column(name="ref_id") private String refId;
    @Column(name="created_at", nullable=false) private OffsetDateTime createdAt = OffsetDateTime.now();

    public StockMovementEntity(Long productId, Integer qty, String reason, String refId){
        this.productId=productId; this.qty=qty; this.reason=reason; this.refId=refId;
    }
}
