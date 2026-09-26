package com.wuling.marketing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.marketing.entity.Coupon;
import com.wuling.marketing.entity.ExchangeOrder;
import com.wuling.marketing.entity.PointsCategory;
import com.wuling.marketing.mapper.ExchangeOrderMapper;
import com.wuling.marketing.entity.PointsEarningRule;
import com.wuling.marketing.entity.PointsProduct;
import com.wuling.marketing.entity.PointsRecord;
import com.wuling.marketing.entity.PointsSignin;
import com.wuling.marketing.entity.PointsSigninRule;
import com.wuling.marketing.entity.UserCoupon;
import com.wuling.marketing.mapper.CouponMapper;
import com.wuling.marketing.mapper.PointsCategoryMapper;
import com.wuling.marketing.mapper.PointsEarningRuleMapper;
import com.wuling.marketing.mapper.PointsProductMapper;
import com.wuling.marketing.mapper.PointsRecordMapper;
import com.wuling.marketing.mapper.PointsSigninMapper;
import com.wuling.marketing.mapper.PointsSigninRuleMapper;
import com.wuling.marketing.mapper.UserCouponMapper;
import com.wuling.user.entity.AppUser;
import com.wuling.user.mapper.AppUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/** 时光币（积分）：获取 / 签到 / 兑换 */
@Service
public class PointsService {

    private static final Logger log = LoggerFactory.getLogger(PointsService.class);


    public static final String PENDING = "PENDING";
    public static final String VERIFIED = "VERIFIED";
    public static final String COMPLETED = "COMPLETED";
    public static final String SOURCE_POINTS_EXCHANGE = "POINTS_EXCHANGE";
    public static final String CATEGORY_COUPON = "coupon";

    private final PointsProductMapper pointsProductMapper;
    private final PointsRecordMapper pointsRecordMapper;
    private final PointsSigninMapper pointsSigninMapper;
    private final PointsSigninRuleMapper pointsSigninRuleMapper;
    private final PointsEarningRuleMapper pointsEarningRuleMapper;
    private final AppUserMapper appUserMapper;
    private final ExchangeOrderMapper exchangeOrderMapper;
    private final PointsCategoryMapper pointsCategoryMapper;
    private final CouponMapper couponMapper;
    private final UserCouponMapper userCouponMapper;

    public PointsService(PointsProductMapper pointsProductMapper,
                         PointsRecordMapper pointsRecordMapper,
                         PointsSigninMapper pointsSigninMapper,
                         PointsSigninRuleMapper pointsSigninRuleMapper,
                         PointsEarningRuleMapper pointsEarningRuleMapper,
                         AppUserMapper appUserMapper,
                         ExchangeOrderMapper exchangeOrderMapper,
                         PointsCategoryMapper pointsCategoryMapper,
                         CouponMapper couponMapper,
                         UserCouponMapper userCouponMapper) {
        this.pointsProductMapper = pointsProductMapper;
        this.pointsRecordMapper = pointsRecordMapper;
        this.pointsSigninMapper = pointsSigninMapper;
        this.pointsSigninRuleMapper = pointsSigninRuleMapper;
        this.pointsEarningRuleMapper = pointsEarningRuleMapper;
        this.appUserMapper = appUserMapper;
        this.exchangeOrderMapper = exchangeOrderMapper;
        this.pointsCategoryMapper = pointsCategoryMapper;
        this.couponMapper = couponMapper;
        this.userCouponMapper = userCouponMapper;
    }

    /** 小程序积分商城分类，仅返回启用项。 */
    public List<PointsCategory> listCategories() {
        return pointsCategoryMapper.selectList(new LambdaQueryWrapper<PointsCategory>()
                .eq(PointsCategory::getEnabled, 1)
                .orderByAsc(PointsCategory::getSort)
                .orderByAsc(PointsCategory::getId));
    }

    public List<PointsProduct> listProducts(String category) {
        List<PointsCategory> categories = listCategories();
        if (categories.isEmpty()) {
            return List.of();
        }
        String requested = category == null ? null : category.trim();
        boolean all = requested == null || requested.isEmpty() || "all".equalsIgnoreCase(requested);
        if (!all && categories.stream().noneMatch(item -> requested.equals(item.getCode()))) {
            return List.of();
        }
        List<String> categoryCodes = categories.stream()
                .map(PointsCategory::getCode)
                .collect(Collectors.toList());
        return pointsProductMapper.selectList(new LambdaQueryWrapper<PointsProduct>()
                .eq(PointsProduct::getStatus, "enabled")
                .eq(!all, PointsProduct::getCategory, requested)
                .in(all, PointsProduct::getCategory, categoryCodes)
                .orderByAsc(PointsProduct::getId));
    }

    public List<PointsEarningRule> earningRules() {
        return pointsEarningRuleMapper.selectList(new LambdaQueryWrapper<PointsEarningRule>()
                .orderByAsc(PointsEarningRule::getSort).orderByAsc(PointsEarningRule::getId));
    }

    public List<PointsRecord> records(Long userId) {
        return pointsRecordMapper.selectList(new LambdaQueryWrapper<PointsRecord>()
                .eq(PointsRecord::getUserId, userId)
                .orderByDesc(PointsRecord::getId));
    }

    /**
     * 增减时光币（唯一入口，保证余额与流水一致）。
     *
     * 并发安全（第 0 期加固）：余额增减改为原子 UPDATE
     * `points = points + ? where points + ? >= 0`，由 InnoDB 行锁串行，
     * 消除「读余额 -> 计算 -> 写回」的丢失更新。
     */
    @Transactional(rollbackFor = Exception.class)
    public long change(Long userId, String type, long amount, String source, String orderNo, String remark) {
        AppUser user = appUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        // 原子增减：条件 points + amount >= 0 保证不会扣成负数
        if (appUserMapper.addPoints(userId, amount) == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "时光币不足");
        }
        long next = (user.getPoints() == null ? 0L : user.getPoints()) + amount;

        PointsRecord record = new PointsRecord();
        record.setUserId(userId);
        record.setType(type);
        record.setAmount(amount);
        record.setBalanceAfter(next);
        record.setSource(source);
        record.setOrderNo(orderNo);
        record.setRemark(remark);
        pointsRecordMapper.insert(record);
        return next;
    }

    /** 用户所有签到日期（yyyy-MM-dd，倒序），供小程序端还原签到状态 */
    public List<String> signinDates(Long userId) {
        return pointsSigninMapper.selectList(new LambdaQueryWrapper<PointsSignin>()
                .eq(PointsSignin::getUserId, userId)
                .orderByDesc(PointsSignin::getSignDate))
                .stream()
                .map(item -> item.getSignDate() == null ? "" : item.getSignDate().toString())
                .filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.toList());
    }

    /** 每日签到（幂等：同一天重复签到被拒绝） */
    @Transactional(rollbackFor = Exception.class)
    public long signIn(Long userId) {
        LocalDate today = LocalDate.now();
        // 并发安全：不再「先查后插」，直接插入并依赖唯一索引
        // uk_points_signin(user_id, sign_date) 拦截并发重复签到，避免重复发放时光币。
        PointsSignin signin = new PointsSignin();
        signin.setUserId(userId);
        signin.setSignDate(today);
        try {
            pointsSigninMapper.insert(signin);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "今日已签到");
        }

        PointsSigninRule rule = pointsSigninRuleMapper.selectList(
                new LambdaQueryWrapper<PointsSigninRule>().orderByAsc(PointsSigninRule::getId))
                .stream().findFirst().orElse(null);
        long daily = rule == null || rule.getDaily() == null ? 1L : rule.getDaily();
        long balance = change(userId, "EARN", daily, "signin", null, "每日签到");
        // 关键业务日志：签到发放时光币
        log.info("签到成功 userId={} 发放={} 余额={}", userId, daily, balance);
        return balance;
    }

    /** 积分兑换：普通商品生成自提码，优惠券商品直接发放到券包。 */
    @Transactional(rollbackFor = Exception.class)
    public ExchangeOrder exchange(Long userId, Long productId, Integer requestedQuantity) {
        int quantity = requestedQuantity == null ? 1 : requestedQuantity;
        if (quantity <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "兑换数量不合法");
        }

        PointsProduct product = pointsProductMapper.selectByIdForUpdate(productId);
        if (product == null || !"enabled".equals(product.getStatus())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "兑换商品不存在");
        }

        PointsCategory category = pointsCategoryMapper.selectOne(new LambdaQueryWrapper<PointsCategory>()
                .eq(PointsCategory::getCode, product.getCategory())
                .eq(PointsCategory::getEnabled, 1)
                .last("limit 1"));
        if (category == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "积分商品分类不存在或已停用");
        }

        boolean couponProduct = CATEGORY_COUPON.equals(product.getCategory());
        Coupon coupon = null;
        if (couponProduct) {
            // 优惠券商品按张兑换，服务端强制固定为 1。
            quantity = 1;
            if (product.getCouponId() == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "优惠券商品未绑定优惠券");
            }
            coupon = couponMapper.selectById(product.getCouponId());
            if (coupon == null || !"enabled".equals(coupon.getStatus())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "绑定优惠券不存在或已停用");
            }
        } else if (product.getCouponId() != null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "普通分类商品不能绑定优惠券");
        }

        Long usedQuantity = exchangeOrderMapper.sumSuccessfulQuantity(userId, productId);
        long used = usedQuantity == null ? 0L : usedQuantity;
        Integer purchaseLimit = product.getPurchaseLimit();
        if (purchaseLimit != null && purchaseLimit > 0
                && used + quantity > purchaseLimit) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "已达到每人限兑次数");
        }

        long unitPoints = product.getPoints() == null ? 0L : product.getPoints();
        long points;
        try {
            points = Math.multiplyExact(unitPoints, quantity);
        } catch (ArithmeticException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "兑换积分超出范围");
        }

        // 并发安全：原子扣库存，扣币或后续步骤失败时整体事务回滚。
        if (pointsProductMapper.deductStock(productId, quantity) == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "库存不足");
        }
        change(userId, "CONSUME", -points, "exchange", null, "兑换 " + product.getName());

        String exchangeNo = nextExchangeNo();
        String pickupCode = null;
        if (couponProduct) {
            if (couponMapper.deductStock(coupon.getId()) == 0) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "优惠券库存不足");
            }
            UserCoupon userCoupon = new UserCoupon();
            userCoupon.setUserId(userId);
            userCoupon.setCouponId(coupon.getId());
            userCoupon.setStatus("UNUSED");
            userCoupon.setReceiveTime(LocalDateTime.now());
            userCoupon.setSource(SOURCE_POINTS_EXCHANGE);
            userCouponMapper.insert(userCoupon);
        } else {
            pickupCode = exchangeNo;
        }

        ExchangeOrder order = new ExchangeOrder();
        order.setExchangeNo(exchangeNo);
        order.setUserId(userId);
        order.setPointsProductId(product.getId());
        order.setPoints(points);
        order.setQuantity(quantity);
        order.setPickupCode(pickupCode);
        order.setStatus(couponProduct ? COMPLETED : PENDING);
        exchangeOrderMapper.insert(order);
        log.info("兑换成功 userId={} productId={} product={} quantity={} 消耗={} 状态={} 自提码={}",
                userId, productId, product.getName(), quantity, points, order.getStatus(), pickupCode);
        return order;
    }

    /** 我的兑换记录 */
    public List<ExchangeOrder> exchangeOrders(Long userId) {
        return exchangeOrderMapper.selectList(new LambdaQueryWrapper<ExchangeOrder>()
                .eq(ExchangeOrder::getUserId, userId)
                .orderByDesc(ExchangeOrder::getId));
    }

    /** 核销兑换单（幂等：已核销的重复核销会被拒绝） */
    @Transactional(rollbackFor = Exception.class)
    public ExchangeOrder verifyExchange(String pickupCode) {
        ExchangeOrder order = exchangeOrderMapper.selectOne(new LambdaQueryWrapper<ExchangeOrder>()
                .eq(ExchangeOrder::getPickupCode, pickupCode)
                .last("limit 1 for update"));
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "兑换码无效");
        }
        if (VERIFIED.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "兑换码已核销，请勿重复核销");
        }
        if (COMPLETED.equals(order.getStatus()) || order.getPickupCode() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该兑换单无需门店核销");
        }
        if (!PENDING.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "兑换单状态不可核销");
        }
        order.setStatus(VERIFIED);
        exchangeOrderMapper.updateById(order);
        return order;
    }

    /** 自提码：CZ + 时间戳，保证唯一 */
    private String nextExchangeNo() {
        return "CZ" + System.currentTimeMillis() % 100000000000000L;
    }
}
