package kr.hhplus.be.server.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.OffsetDateTime;

@Entity
@Table(name="coupons")
@Getter
public class CouponEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(unique=true, nullable=false) private String code;
    @Column(nullable=false) private String type; // PERCENT/FIXED
    @Column(nullable=false) private Integer value;
    @Column(name="min_amount") private Integer minAmount;
    @Column(name="max_discount") private Integer maxDiscount;
    private OffsetDateTime startsAt; private OffsetDateTime endsAt;
}
