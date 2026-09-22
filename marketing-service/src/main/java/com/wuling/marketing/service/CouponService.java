package com.wuling.marketing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.marketing.entity.Coupon;
import com.wuling.marketing.entity.UserCoupon;
import com.wuling.marketing.mapper.CouponMapper;
import com.wuling.marketing.mapper.UserCouponMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 优惠券：领取 / 锁券 / 核销 / 回退。
 * 锁券 -> 下单占用；支付成功核销；取消订单或超时回退。
 */
@Service
public class CouponService {

    public static final String UNUSED = "UNUSED";
    public static final String LOCKED = "LOCKED";
    public static final String USED = "USED";
    public static final String EXPIRED = "EXPIRED";

    private final CouponMapper couponMapper;
    private final UserCouponMapper userCouponMapper;

    public CouponService(CouponMapper couponMapper, UserCouponMapper userCouponMapper) {
        this.couponMapper = couponMapper;
        this.userCouponMapper = userCouponMapper;
    }

    public List<Coupon> listEnabled() {
        return couponMapper.selectList(new LambdaQueryWrapper<Coupon>()
                .eq(Coupon::getStatus, "enabled")
                .orderByAsc(Coupon::getId));
    }

    public List<UserCoupon> myCoupons(Long userId, String status) {
        LambdaQueryWrapper<UserCoupon> query = new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getUserId, userId)
                .orderByDesc(UserCoupon::getId);
        if (status != null && !status.isBlank()) {
            query.eq(UserCoupon::getStatus, status);
        }
        return userCouponMapper.selectList(query);
    }

    /**
     * 领取优惠券（防超发、防重复领取）。
     *
     * 并发安全设计（第 0 期加固）：
     * - 库存：改为原子 UPDATE（stock = stock - 1 where stock > 0），不再先查后扣；
     * - 重复领取：依赖 user_coupon 唯一索引 uk_user_coupon_active，
     *   并发重复提交时数据库抛 DuplicateKeyException，转为友好提示；
     * - 顺序：先落持有记录，再扣库存，任一失败整体回滚。
     */
    @Transactional(rollbackFor = Exception.class)
    public UserCoupon receive(Long userId, Long couponId) {
        Coupon coupon = couponMapper.selectById(couponId);
        if (coupon == null || !"enabled".equals(coupon.getStatus())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "优惠券不存在或已停用");
        }

        // 先落持有记录：唯一索引在此拦截并发重复领取
        UserCoupon uc = new UserCoupon();
        uc.setUserId(userId);
        uc.setCouponId(couponId);
        uc.setStatus(UNUSED);
        uc.setReceiveTime(LocalDateTime.now());
        try {
            userCouponMapper.insert(uc);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "您已领取该优惠券");
        }

        // 再扣库存：原子 SQL 保证不超发
        if (couponMapper.deductStock(couponId) == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "优惠券已领完");
        }
        return uc;
    }

    /** 锁券（下单占用），返回可抵扣金额（分） */
    @Transactional(rollbackFor = Exception.class)
    public long lock(Long userId, Long userCouponId, Long orderId, long orderAmount) {
        UserCoupon uc = userCouponMapper.selectById(userCouponId);
        if (uc == null || !uc.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "优惠券不存在");
        }
        if (!UNUSED.equals(uc.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "优惠券已使用或已锁定");
        }
        Coupon coupon = couponMapper.selectById(uc.getCouponId());
        if (coupon == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "优惠券模板不存在");
        }
        long threshold = coupon.getThreshold() == null ? 0L : coupon.getThreshold();
        if (orderAmount < threshold) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单金额未达到优惠券使用门槛");
        }
        uc.setStatus(LOCKED);
        uc.setLockOrderId(orderId);
        userCouponMapper.updateById(uc);
        long amount = coupon.getAmount() == null ? 0L : coupon.getAmount();
        return Math.min(amount, orderAmount);
    }

    /** 核销（支付成功后） */
    @Transactional(rollbackFor = Exception.class)
    public void consume(Long userCouponId) {
        UserCoupon uc = userCouponMapper.selectById(userCouponId);
        if (uc == null || !LOCKED.equals(uc.getStatus())) {
            return;
        }
        uc.setStatus(USED);
        uc.setUseTime(LocalDateTime.now());
        userCouponMapper.updateById(uc);
    }

    /** 回退（订单取消/超时） */
    @Transactional(rollbackFor = Exception.class)
    public void releaseByOrder(Long orderId) {
        List<UserCoupon> list = userCouponMapper.selectList(new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getLockOrderId, orderId)
                .eq(UserCoupon::getStatus, LOCKED));
        for (UserCoupon uc : list) {
            uc.setStatus(UNUSED);
            uc.setLockOrderId(null);
            userCouponMapper.updateById(uc);
        }
    }
}

