package com.wuling.marketing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.marketing.entity.Coupon;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface CouponMapper extends BaseMapper<Coupon> {

    /**
     * 原子扣减库存（防超发）。
     * 并发安全要点：把「校验 stock > 0 + 扣减」合并为单条 UPDATE，
     * 由 InnoDB 行锁保证串行，无需应用层加锁。
     *
     * @return 1=扣减成功；0=库存不足或券已停用（调用方据此判定"已领完"）
     */
    @Update("update coupon set stock = stock - 1, update_time = now() "
            + "where id = #{couponId} and stock > 0 and status = 'enabled' and deleted = 0")
    int deductStock(@Param("couponId") Long couponId);
}
