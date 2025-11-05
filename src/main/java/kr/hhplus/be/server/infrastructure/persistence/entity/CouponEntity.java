package kr.hhplus.be.server.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name="coupons")
@Getter
@Setter
public class CouponEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(unique=true, nullable=false) private String code;
    @Column(nullable=false) private String type; // PERCENT/FIXED
    @Column(nullable=false) private Integer value;
    @Column(name="min_amount") private Integer minAmount;
    @Column(name="max_discount") private Integer maxDiscount;
    @Column(name="max_issuance") private Integer maxIssuance; // 최대 발급 수량 (선착순 제한)
    @Column(name="issued_count", nullable=false) private Integer issuedCount = 0; // 현재 발급된 수량
    private OffsetDateTime startsAt;
    private OffsetDateTime endsAt;

    @Version
    private Long version; // Optimistic Lock용 버전 필드
}
