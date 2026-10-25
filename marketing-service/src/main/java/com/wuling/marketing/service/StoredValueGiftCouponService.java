package com.wuling.marketing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.marketing.entity.Coupon;
import com.wuling.marketing.entity.StoredValuePackageCoupon;
import com.wuling.marketing.entity.UserCoupon;
import com.wuling.marketing.mapper.CouponMapper;
import com.wuling.marketing.mapper.StoredValuePackageCouponMapper;
import com.wuling.marketing.mapper.UserCouponMapper;
import com.wuling.marketing.subscribe.MarketingSubscribeNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 储值套餐赠券发放：按套餐配置实际写入用户券包，并支持同一模板多张。 */
@Service
public class StoredValueGiftCouponService {

    private static final Logger log = LoggerFactory.getLogger(StoredValueGiftCouponService.class);

    private final StoredValuePackageCouponMapper packageCouponMapper;
    private final CouponMapper couponMapper;
    private final UserCouponMapper userCouponMapper;
    private final MarketingSubscribeNotifier subscribeNotifier;

    public StoredValueGiftCouponService(StoredValuePackageCouponMapper packageCouponMapper,
                                        CouponMapper couponMapper,
                                        UserCouponMapper userCouponMapper,
                                        MarketingSubscribeNotifier subscribeNotifier) {
        this.packageCouponMapper = packageCouponMapper;
        this.couponMapper = couponMapper;
        this.userCouponMapper = userCouponMapper;
        this.subscribeNotifier = subscribeNotifier;
    }

    @Transactional(rollbackFor = Exception.class)
    public CouponGrantSummary issue(Long userId, Long packageId, String orderNo) {
        List<StoredValuePackageCoupon> links = packageCouponMapper.selectList(
                new LambdaQueryWrapper<StoredValuePackageCoupon>()
                        .eq(StoredValuePackageCoupon::getPackageId, packageId));
        if (links == null || links.isEmpty()) {
            return CouponGrantSummary.empty();
        }

        int total = 0;
        LocalDateTime earliestExpireAt = null;
        Set<String> names = new LinkedHashSet<>();
        LocalDateTime receiveTime = LocalDateTime.now();
        for (StoredValuePackageCoupon link : links) {
            if (link == null || link.getCouponId() == null || link.getCount() == null || link.getCount() <= 0) {
                continue;
            }
            Coupon coupon = couponMapper.selectById(link.getCouponId());
            if (coupon == null || !"enabled".equalsIgnoreCase(coupon.getStatus())) {
                log.warn("储值赠券模板不存在或已停用，跳过 packageId={} couponId={}", packageId, link.getCouponId());
                continue;
            }
            for (int index = 1; index <= link.getCount(); index++) {
                UserCoupon userCoupon = new UserCoupon();
                userCoupon.setUserId(userId);
                userCoupon.setCouponId(coupon.getId());
                userCoupon.setStatus(CouponService.UNUSED);
                userCoupon.setSource("STORED_VALUE");
                userCoupon.setGrantNo("SV:" + orderNo + ":" + coupon.getId() + ":" + index);
                userCoupon.setReceiveTime(receiveTime);
                try {
                    if (userCouponMapper.insert(userCoupon) != 1) {
                        continue;
                    }
                } catch (DuplicateKeyException duplicate) {
                    continue;
                }
                if (couponMapper.deductStock(coupon.getId()) == 0) {
                    if (userCoupon.getId() != null) {
                    userCouponMapper.deleteById(userCoupon.getId());
                }
                    log.warn("储值赠券库存不足，跳过 packageId={} couponId={}", packageId, coupon.getId());
                    continue;
                }
                total += 1;
                names.add(coupon.getName());
                LocalDateTime expireAt = expireAt(coupon, receiveTime);
                if (expireAt != null && (earliestExpireAt == null || expireAt.isBefore(earliestExpireAt))) {
                    earliestExpireAt = expireAt;
                }
            }
        }

        if (total <= 0) {
            return CouponGrantSummary.empty();
        }
        CouponGrantSummary summary = new CouponGrantSummary(
                total,
                names.size() == 1 ? names.iterator().next() : "充值赠券",
                earliestExpireAt,
                new ArrayList<>(names));
        if (subscribeNotifier != null) {
            subscribeNotifier.notifyCouponArrival(
                    userId,
                    summary.couponName(),
                    summary.quantity(),
                    summary.earliestExpireAt() == null ? receiveTime : summary.earliestExpireAt(),
                    "已放入券包，请及时使用",
                    "COUPON_ARRIVAL:STORED_VALUE:" + orderNo);
        }
        return summary;
    }

    static LocalDateTime expireAt(Coupon coupon, LocalDateTime receiveTime) {
        if (coupon == null) {
            return null;
        }
        if ("RANGE".equalsIgnoreCase(coupon.getValidityType())) {
            return coupon.getValidityEnd();
        }
        if ("DAYS".equalsIgnoreCase(coupon.getValidityType())
                && coupon.getValidityDays() != null && coupon.getValidityDays() > 0) {
            int delay = coupon.getEffectiveDelayDays() == null || coupon.getEffectiveDelayDays() < 0
                    ? 0 : coupon.getEffectiveDelayDays();
            return receiveTime.plusDays(delay).plusDays(coupon.getValidityDays());
        }
        return null;
    }

    public record CouponGrantSummary(int quantity,
                                     String couponName,
                                     LocalDateTime earliestExpireAt,
                                     List<String> couponNames) {
        public static CouponGrantSummary empty() {
            return new CouponGrantSummary(0, "", null, List.of());
        }

        public boolean isEmpty() {
            return quantity <= 0;
        }
    }
}
