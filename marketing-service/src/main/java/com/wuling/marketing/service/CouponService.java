package com.wuling.marketing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.marketing.dto.UserCouponView;
import com.wuling.marketing.entity.Coupon;
import com.wuling.marketing.entity.UserCoupon;
import com.wuling.marketing.mapper.CouponMapper;
import com.wuling.marketing.mapper.UserCouponMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    /**
     * 我的可用券。
     *
     * <p>列表与「我的」页统计共用本方法，统一只返回当前状态为 UNUSED 且仍在有效期内
     * 的用户券。展示 ID 始终使用 user_coupon.id，避免同一模板多张券被 code 覆盖。
     */
    public List<UserCouponView> myCoupons(Long userId, String status) {
        LambdaQueryWrapper<UserCoupon> query = new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getUserId, userId)
                .orderByDesc(UserCoupon::getId);
        if (status != null && !status.isBlank()) {
            query.eq(UserCoupon::getStatus, status);
        }
        List<UserCoupon> userCoupons = userCouponMapper.selectList(query);
        if (userCoupons == null || userCoupons.isEmpty()) {
            return List.of();
        }

        List<Long> couponIds = userCoupons.stream()
                .map(UserCoupon::getCouponId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Coupon> couponById = new LinkedHashMap<>();
        if (!couponIds.isEmpty()) {
            List<Coupon> coupons = couponMapper.selectBatchIds(couponIds);
            if (coupons != null) {
                for (Coupon coupon : coupons) {
                    if (coupon != null && coupon.getId() != null) {
                        couponById.put(coupon.getId(), coupon);
                    }
                }
            }
        }

        LocalDateTime now = LocalDateTime.now();
        List<UserCouponView> result = new ArrayList<>();
        for (UserCoupon userCoupon : userCoupons) {
            if (userCoupon == null || !UNUSED.equals(userCoupon.getStatus())) {
                continue;
            }
            Coupon coupon = couponById.get(userCoupon.getCouponId());
            CouponValidity validity = resolveValidity(userCoupon, coupon, now);
            if (!validity.usable()) {
                continue;
            }
            result.add(toView(userCoupon, coupon, validity));
        }
        return result;
    }

    private UserCouponView toView(UserCoupon userCoupon, Coupon coupon, CouponValidity validity) {
        UserCouponView view = new UserCouponView();
        view.setId(userCoupon.getId());
        view.setUserId(userCoupon.getUserId());
        view.setCouponId(userCoupon.getCouponId());
        view.setStatus(userCoupon.getStatus());
        view.setReceiveTime(userCoupon.getReceiveTime());
        view.setLockOrderId(userCoupon.getLockOrderId());
        view.setUseTime(userCoupon.getUseTime());
        view.setUsable(true);
        view.setExpireAt(validity.expireAt());

        if (coupon == null) {
            return view;
        }
        view.setCouponCode(coupon.getCode());
        view.setName(coupon.getName());
        view.setType(coupon.getType());
        view.setAmount(coupon.getAmount());
        view.setThreshold(coupon.getThreshold());
        view.setBrand(coupon.getBrand());
        view.setScenes(coupon.getScenes());
        view.setSource(coupon.getSource());
        view.setDescription(coupon.getDescription());
        view.setImage(coupon.getImage());
        view.setValidityType(coupon.getValidityType());
        view.setValidityStart(coupon.getValidityStart());
        view.setValidityEnd(coupon.getValidityEnd());
        view.setValidityDays(coupon.getValidityDays());
        return view;
    }

    /**
     * 列表与锁券共用的有效期判定。
     * RANGE 使用模板起止时间；DAYS 使用领取时间 + 天数；未知类型视为长期有效。
     */
    private CouponValidity resolveValidity(UserCoupon userCoupon, Coupon coupon, LocalDateTime now) {
        if (coupon == null || !"enabled".equalsIgnoreCase(coupon.getStatus())) {
            return new CouponValidity(false, null);
        }

        String validityType = coupon.getValidityType();
        if (validityType == null || validityType.isBlank()) {
            return new CouponValidity(true, null);
        }
        if ("RANGE".equalsIgnoreCase(validityType)) {
            LocalDateTime start = coupon.getValidityStart();
            LocalDateTime end = coupon.getValidityEnd();
            boolean usable = start != null && end != null
                    && !now.isBefore(start)
                    && !now.isAfter(end);
            return new CouponValidity(usable, end);
        }
        if ("DAYS".equalsIgnoreCase(validityType)) {
            LocalDateTime receiveTime = userCoupon.getReceiveTime();
            Integer validityDays = coupon.getValidityDays();
            if (receiveTime == null || validityDays == null || validityDays <= 0) {
                return new CouponValidity(false, null);
            }
            LocalDateTime expireAt = receiveTime.plusDays(validityDays);
            boolean usable = !now.isBefore(receiveTime) && !now.isAfter(expireAt);
            return new CouponValidity(usable, expireAt);
        }
        return new CouponValidity(true, null);
    }

    private record CouponValidity(boolean usable, LocalDateTime expireAt) {
    }
    /**
     * 发放分享有礼奖励券。
     *
     * <p>奖励金额以后台邀请配置为准，按「类型 + 面额 + 无门槛」查找券模板；
     * 首次创建时依赖 coupon.code 唯一键处理并发冲突。持券写入使用
     * {@code INSERT IGNORE}，重复消息或重复持有都视为发放成功，不重复扣库存。
     */
    @Transactional(rollbackFor = Exception.class)
    public void issueReferralCoupon(Long userId, Long amountFen) {
        if (userId == null || amountFen == null || amountFen < 0) {
            throw new IllegalArgumentException("邀请奖励券参数非法");
        }

        Coupon coupon = findReferralCoupon(amountFen);
        if (coupon == null) {
            coupon = buildReferralCoupon(amountFen);
            try {
                couponMapper.insert(coupon);
            } catch (DuplicateKeyException e) {
                // 并发首次发奖：另一个消费者已创建模板，回查后复用。
                coupon = findReferralCoupon(amountFen);
                if (coupon == null) {
                    throw e;
                }
            }
        }

        int inserted = userCouponMapper.insertIgnoreActive(userId, coupon.getId());
        if (inserted == 0) {
            // 同一用户已持有该券模板，说明奖励已发，重复消息直接成功返回。
            return;
        }
        if (couponMapper.deductStock(coupon.getId()) == 0) {
            throw new IllegalStateException("邀请奖励券库存不足 couponId=" + coupon.getId());
        }
    }

    private Coupon findReferralCoupon(Long amountFen) {
        return couponMapper.selectOne(new LambdaQueryWrapper<Coupon>()
                .eq(Coupon::getType, "REFERRAL")
                .eq(Coupon::getAmount, amountFen)
                .eq(Coupon::getThreshold, 0L)
                .eq(Coupon::getStatus, "enabled")
                .orderByAsc(Coupon::getId)
                .last("limit 1"));
    }

    private Coupon buildReferralCoupon(Long amountFen) {
        Coupon coupon = new Coupon();
        coupon.setCode("referral-coupon-" + formatYuan(amountFen));
        coupon.setName("分享有礼" + formatYuan(amountFen) + "元无门槛券");
        coupon.setType("REFERRAL");
        coupon.setAmount(amountFen);
        coupon.setThreshold(0L);
        coupon.setBrand("五零时光");
        coupon.setScenes("买单");
        coupon.setSource("分享有礼");
        coupon.setDescription("好友通过邀请链接注册并完成首单，奖励" + formatYuan(amountFen) + "元无门槛券。");
        coupon.setImage("/assets/images/3x/menu-product.jpg");
        coupon.setValidityType("DAYS");
        coupon.setValidityDays(15);
        coupon.setUsageTime("00:00:00~23:59:59");
        coupon.setStock(100000);
        coupon.setStatus("enabled");
        return coupon;
    }

    private String formatYuan(Long amountFen) {
        if (amountFen % 100 == 0) {
            return String.valueOf(amountFen / 100);
        }
        return java.math.BigDecimal.valueOf(amountFen, 2).stripTrailingZeros().toPlainString();
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
        uc.setSource("RECEIVE");
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
        CouponValidity validity = resolveValidity(uc, coupon, LocalDateTime.now());
        if (!validity.usable()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "优惠券已过期或不可用");
        }        long threshold = coupon.getThreshold() == null ? 0L : coupon.getThreshold();
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

