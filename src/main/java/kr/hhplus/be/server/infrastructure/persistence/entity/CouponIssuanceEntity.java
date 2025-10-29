package kr.hhplus.be.server.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name="coupon_issuances", uniqueConstraints = {
        @UniqueConstraint(name="uq_issue", columnNames={"coupon_id","user_id"})
})
@Getter
@Setter
@NoArgsConstructor
public class CouponIssuanceEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="coupon_id", nullable=false) private Long couponId;
    @Column(name="user_id", nullable=false) private Long userId;
    @Column(name="redeem_count", nullable=false) private Integer redeemCount = 0;
}
