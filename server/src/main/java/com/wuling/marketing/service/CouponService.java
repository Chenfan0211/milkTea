package com.wuling.marketing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.marketing.entity.Coupon;
import com.wuling.marketing.entity.UserCoupon;
import com.wuling.marketing.mapper.CouponMapper;
import com.wuling.marketing.mapper.UserCouponMapper;
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

    /** 领取优惠券（扣库存；同一用户同一券只允许持有一张未使用） */
    @Transactional(rollbackFor = Exception.class)
    public UserCoupon receive(Long userId, Long couponId) {
        Coupon coupon = couponMapper.selectById(couponId);
        if (coupon == null || !"enabled".equals(coupon.getStatus())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "优惠券不存在或已停用");
        }
        if (coupon.getStock() != null && coupon.getStock() <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "优惠券已领完");
        }
        Long owned = userCouponMapper.selectCount(new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getUserId, userId)
                .eq(UserCoupon::getCouponId, couponId)
                .eq(UserCoupon::getStatus, UNUSED));
        if (owned != null && owned > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "您已领取该优惠券");
        }
        Coupon patch = new Coupon();
        patch.setId(coupon.getId());
        patch.setStock(coupon.getStock() - 1);
        couponMapper.updateById(patch);

        UserCoupon uc = new UserCoupon();
        uc.setUserId(userId);
        uc.setCouponId(couponId);
        uc.setStatus(UNUSED);
        uc.setReceiveTime(LocalDateTime.now());
        userCouponMapper.insert(uc);
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

