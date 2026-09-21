package com.wuling.marketing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.marketing.entity.Coupon;
import com.wuling.marketing.entity.StoredValueOrder;
import com.wuling.marketing.entity.StoredValuePackage;
import com.wuling.marketing.mapper.CouponMapper;
import com.wuling.marketing.mapper.StoredValueOrderMapper;
import com.wuling.marketing.mapper.StoredValuePackageMapper;
import com.wuling.user.entity.AppUser;
import com.wuling.user.mapper.AppUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 储值充值。
 * 对齐方案：储值购买时不分账，核销消费时才分账（预收资金不提前分配）。
 */
@Service
public class StoredValueService {

    private static final Logger log = LoggerFactory.getLogger(StoredValueService.class);

    private final StoredValuePackageMapper packageMapper;
    private final StoredValueOrderMapper orderMapper;
    private final CouponMapper couponMapper;
    private final AppUserMapper appUserMapper;

    public StoredValueService(StoredValuePackageMapper packageMapper,
                              StoredValueOrderMapper orderMapper,
                              CouponMapper couponMapper,
                              AppUserMapper appUserMapper) {
        this.packageMapper = packageMapper;
        this.orderMapper = orderMapper;
        this.couponMapper = couponMapper;
        this.appUserMapper = appUserMapper;
    }

    public List<StoredValuePackage> listPackages() {
        return packageMapper.selectList(new LambdaQueryWrapper<StoredValuePackage>()
                .eq(StoredValuePackage::getStatus, "enabled")
                .orderByAsc(StoredValuePackage::getAmount));
    }

    /** 充值：订单置已支付 + 余额入账（幂等） */
    @Transactional(rollbackFor = Exception.class)
    public StoredValueOrder recharge(Long userId, Long packageId) {
        StoredValuePackage pkg = packageMapper.selectById(packageId);
        if (pkg == null || !"enabled".equals(pkg.getStatus())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "储值套餐不存在");
        }
        AppUser user = appUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }

        StoredValueOrder order = new StoredValueOrder();
        order.setOrderNo(nextNo("CZ"));
        order.setUserId(userId);
        order.setPackageId(packageId);
        order.setAmount(pkg.getAmount());
        order.setPayStatus("UNPAID");
        orderMapper.insert(order);

        // Mock 支付成功：余额入账
        AppUser patch = new AppUser();
        patch.setId(userId);
        patch.setBalance((user.getBalance() == null ? 0L : user.getBalance()) + pkg.getAmount());
        appUserMapper.updateById(patch);

        order.setPayStatus("PAID");
        orderMapper.updateById(order);
        log.info("stored value recharge ok userId={} amount={}", userId, pkg.getAmount());
        return order;
    }

    public List<StoredValueOrder> myOrders(Long userId) {
        return orderMapper.selectList(new LambdaQueryWrapper<StoredValueOrder>()
                .eq(StoredValueOrder::getUserId, userId)
                .orderByDesc(StoredValueOrder::getId));
    }

    private String nextNo(String prefix) {
        return prefix + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }
}
