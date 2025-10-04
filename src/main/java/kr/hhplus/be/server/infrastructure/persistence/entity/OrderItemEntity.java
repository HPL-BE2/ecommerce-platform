package kr.hhplus.be.server.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.Setter;

@Entity
@Table(name="order_items")
@Setter
public class OrderItemEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="order_id", nullable=false) private Long orderId;
    @Column(name="product_id", nullable=false) private Long productId;
    @Column(nullable=false, length=200) private String name;
    @Column(nullable=false) private Integer qty;
    @Column(name="unit_price", nullable=false) private Long unitPrice;
    @Column(name="line_total", nullable=false) private Long lineTotal;
}
