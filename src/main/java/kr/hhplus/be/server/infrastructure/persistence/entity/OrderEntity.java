package kr.hhplus.be.server.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name="orders", indexes = {
        @Index(name="idx_orders_request_key", columnList = "user_id, request_key", unique = true)
})
@Getter @Setter
public class OrderEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="user_id", nullable=false) private Long userId;
    @Column(name="order_no", unique=true, length=32) private String orderNo;
    @Column(nullable=false, length=16) private String status; // RESERVED
    @Column(nullable=false) private Long discount;
    @Column(nullable=false) private Long total;
    @Column(name="request_key", nullable=false, length=100) private String requestKey;
    @Column(name="coupon_issuance_id") private Long couponIssuanceId;
    @Column(nullable=false) private OffsetDateTime createdAt = OffsetDateTime.now();
    @Column(nullable=false) private OffsetDateTime updatedAt = OffsetDateTime.now();
}
