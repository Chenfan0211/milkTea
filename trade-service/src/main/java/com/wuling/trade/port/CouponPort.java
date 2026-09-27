package com.wuling.trade.port;

import java.util.List;

/** 交易域调用营销优惠券能力的同步端口（仅锁券需要同步结果）。 */
public interface CouponPort {

    /**
     * 锁定优惠券。
     *
     * <p>{@code items} 是储值立减后的商品行净额；营销侧只对券模板适用的商品行
     * 计算门槛和抵扣上限。{@code productIds} 保留给旧实现兼容。</p>
     */
    LockResult lock(Long userId,
                    Long userCouponId,
                    String orderNo,
                    Long storeSubjectId,
                    List<Long> productIds,
                    List<ItemAmount> items,
                    String scene,
                    long orderAmount);

    /** 旧调用兼容入口；不传逐商品行明细时按整单金额计算。 */
    default LockResult lock(Long userId,
                            Long userCouponId,
                            String orderNo,
                            Long storeSubjectId,
                            List<Long> productIds,
                            String scene,
                            long orderAmount) {
        return lock(userId, userCouponId, orderNo, storeSubjectId, productIds,
                null, scene, orderAmount);
    }

    /** 锁券后本地下单事务失败时的同步补偿；正常取消/超时走 outbox。 */
    void releaseAfterLockFailure(String orderNo);

    record LockResult(Long userCouponId, Long couponId, long discountAmount) {
    }

    /** 单个订单商品行在储值立减后的净额，单位分。 */
    record ItemAmount(Long productId, long amount) {
    }
}
