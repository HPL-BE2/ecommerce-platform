package kr.hhplus.be.server.infrastructure.coupon;

public final class CouponRedisKeys {
    private CouponRedisKeys() { }

    public static String issuedCount(Long couponId) {
        return "coupon:" + couponId + ":issued";
    }

    public static String remainingCount(Long couponId) {
        return "coupon:" + couponId + ":remaining";
    }

    public static String issuedUsers(Long couponId) {
        return "coupon:" + couponId + ":users";
    }
}
