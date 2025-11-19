package kr.hhplus.be.server.domain.port.out;

import kr.hhplus.be.server.domain.model.Coupon;

import java.util.Optional;

/**
 * Coupon 도메인의 영속성 포트
 */
public interface CouponReadWritePort {
    /**
     * 쿠폰 조회 (ID로)
     * @param couponId 쿠폰 ID
     * @return 쿠폰 정보
     */
    Optional<Coupon> findById(Long couponId);

    /**
     * 쿠폰 발급 수량 조회
     * @param couponId 쿠폰 ID
     * @return 현재 발급된 수량
     */
    long countIssuances(Long couponId);

    /**
     * 쿠폰 발급
     * @param couponId 쿠폰 ID
     * @param userId 사용자 ID
     * @return 발급 ID
     * @throws org.springframework.dao.DataIntegrityViolationException 중복 발급 시
     */
    Long issueCoupon(Long couponId, Long userId);

    /**
     * 쿠폰 발급 여부 확인
     * @param couponId 쿠폰 ID
     * @param userId 사용자 ID
     * @return 이미 발급받았으면 true
     */
    boolean isAlreadyIssued(Long couponId, Long userId);

    /**
     * 쿠폰 발급 취소 (보상 트랜잭션)
     * @param couponId 쿠폰 ID
     * @param userId 사용자 ID
     * @return 삭제 성공 여부
     */
    boolean deleteCouponIssuance(Long couponId, Long userId);
}
