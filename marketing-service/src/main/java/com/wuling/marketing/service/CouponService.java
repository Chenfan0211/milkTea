package com.wuling.marketing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.marketing.dto.internal.CouponLockRequest;
import com.wuling.marketing.dto.UserCouponView;
import com.wuling.marketing.entity.AuditLog;
import com.wuling.marketing.entity.Coupon;
import com.wuling.marketing.entity.UserCoupon;
import com.wuling.marketing.mapper.AuditLogMapper;
import com.wuling.marketing.mapper.CouponMapper;
import com.wuling.marketing.mapper.UserCouponMapper;
import com.wuling.marketing.util.OrderNoCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
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
    /** 虚拟状态：券仍是 UNUSED，但生效时间尚未开始（前端「待生效」Tab）。 */
    public static final String PENDING = "PENDING";

    private final CouponMapper couponMapper;
    private final UserCouponMapper userCouponMapper;
    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 兼容既有单测与旧调用。 */
    public CouponService(CouponMapper couponMapper, UserCouponMapper userCouponMapper) {
        this(couponMapper, userCouponMapper, null);
    }

    @Autowired
    public CouponService(CouponMapper couponMapper,
                         UserCouponMapper userCouponMapper,
                         AuditLogMapper auditLogMapper) {
        this.couponMapper = couponMapper;
        this.userCouponMapper = userCouponMapper;
        this.auditLogMapper = auditLogMapper;
    }
    public List<Coupon> listEnabled() {
        return couponMapper.selectList(new LambdaQueryWrapper<Coupon>()
                .eq(Coupon::getStatus, "enabled")
                .orderByAsc(Coupon::getId));
    }

    /**
     * 我的优惠券（按状态筛选）。
     *
     * <p>status 语义：
     * <ul>
     *   <li>USED / LOCKED / EXPIRED —— 终态，直接按 user_coupon.status 透传；</li>
     *   <li>UNUSED 或不传（旧调用 / 「我的」页统计）—— 返回「已生效且未过期」的可用券；</li>
     *   <li>PENDING —— 虚拟状态，返回「仍是 UNUSED 但生效时间尚未开始」的待生效券。</li>
     * </ul>
     *
     * <p>展示 ID 始终使用 user_coupon.id。
     */
    public List<UserCouponView> myCoupons(Long userId, String status) {
        boolean pending = PENDING.equalsIgnoreCase(status);
        // 终态（USED / LOCKED / EXPIRED）直接按库表状态透传；
        // UNUSED / PENDING / 不传 都从 UNUSED 池里按生效时间进一步区分。
        boolean terminal = USED.equalsIgnoreCase(status)
                || LOCKED.equalsIgnoreCase(status)
                || EXPIRED.equalsIgnoreCase(status);

        LambdaQueryWrapper<UserCoupon> query = new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getUserId, userId)
                .orderByDesc(UserCoupon::getId);
        if (terminal) {
            query.eq(UserCoupon::getStatus, status);
        } else {
            query.eq(UserCoupon::getStatus, UNUSED);
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
            if (userCoupon == null) {
                continue;
            }
            Coupon coupon = couponById.get(userCoupon.getCouponId());
            CouponValidity validity = resolveValidity(userCoupon, coupon, now);
            if (terminal) {
                // 终态直接透传（仅保留与请求状态一致的券，兜底 SQL 过滤失效场景）。
                if (!status.equalsIgnoreCase(userCoupon.getStatus())) {
                    continue;
                }
                UserCouponView view = toView(userCoupon, coupon, validity);
                view.setUsable(false);
                result.add(view);
                continue;
            }
            // UNUSED / PENDING / 不传：兜底过滤，仅处理 UNUSED 状态的券。
            if (!UNUSED.equals(userCoupon.getStatus())) {
                continue;
            }
            if (pending) {
                // 待生效：已领取但尚未到生效时间。
                if (!validity.started()) {
                    result.add(toView(userCoupon, coupon, validity));
                }
                continue;
            }
            // 可用（UNUSED / 不传）：已生效且未过期。
            if (validity.started() && validity.usable()) {
                result.add(toView(userCoupon, coupon, validity));
            }
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
        view.setEffectiveDelayDays(coupon.getEffectiveDelayDays());
        // 适用范围：库里是 JSON 字符串，解析成数字数组下发。
        // 空数组表示不限制，前端据此判定是否需按门店/商品过滤。
        view.setApplicableStoreIds(parseLongList(coupon.getApplicableStoreIds()));
        view.setApplicableProductIds(parseLongList(coupon.getApplicableProductIds()));
        return view;
    }

    /**
     * 列表与锁券共用的有效期判定。
     *
     * <p>RANGE 使用模板起止时间；DAYS 使用领取时间 + 有效天数；未知类型视为长期有效。
     *
     * <p>延迟生效（{@code effectiveDelayDays}）：领取后需等待 N 天才可使用，
     * 生效起点 = 领取时间 + N 天，过期时间 = 生效起点 + 有效天数。
     * 这段时间内的券 {@code started=false}，归入前端「待生效」Tab，
     * 且 {@code usable=false}，锁券时会被拒绝。
     */
    private CouponValidity resolveValidity(UserCoupon userCoupon, Coupon coupon, LocalDateTime now) {
        if (coupon == null || !"enabled".equalsIgnoreCase(coupon.getStatus())) {
            return new CouponValidity(false, null, false);
        }

        String validityType = coupon.getValidityType();
        if (validityType == null || validityType.isBlank()) {
            // 无期限：视为立即生效、长期有效。
            return new CouponValidity(true, null, true);
        }
        if ("RANGE".equalsIgnoreCase(validityType)) {
            LocalDateTime start = coupon.getValidityStart();
            LocalDateTime end = coupon.getValidityEnd();
            boolean started = start == null || !now.isBefore(start);
            boolean usable = start != null && end != null
                    && !now.isBefore(start)
                    && !now.isAfter(end);
            return new CouponValidity(usable, end, started);
        }
        if ("DAYS".equalsIgnoreCase(validityType)) {
            LocalDateTime receiveTime = userCoupon.getReceiveTime();
            Integer validityDays = coupon.getValidityDays();
            if (receiveTime == null || validityDays == null || validityDays <= 0) {
                return new CouponValidity(false, null, false);
            }
            // 延迟生效：领取后需等待 effectiveDelayDays 天才开始生效。
            int delayDays = coupon.getEffectiveDelayDays() == null || coupon.getEffectiveDelayDays() < 0
                    ? 0
                    : coupon.getEffectiveDelayDays();
            LocalDateTime effectiveAt = delayDays == 0 ? receiveTime : receiveTime.plusDays(delayDays);
            LocalDateTime expireAt = effectiveAt.plusDays(validityDays);
            boolean started = !now.isBefore(effectiveAt);
            boolean usable = started && !now.isAfter(expireAt);
            return new CouponValidity(usable, expireAt, started);
        }
        return new CouponValidity(true, null, true);
    }

    private record CouponValidity(boolean usable, LocalDateTime expireAt, boolean started) {
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
        // 只复用「已配置延迟生效」的模板：若历史库里存在同面额但立即生效的
        // 分享有礼券（延迟天数为 NULL/0），此处不应命中，否则会绕过 3 天延迟。
        return couponMapper.selectOne(new LambdaQueryWrapper<Coupon>()
                .eq(Coupon::getType, "REFERRAL")
                .eq(Coupon::getAmount, amountFen)
                .eq(Coupon::getThreshold, 0L)
                .eq(Coupon::getStatus, "enabled")
                .gt(Coupon::getEffectiveDelayDays, 0)
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
        // 分享有礼券领取后 3 天生效：防止「好友完成首单即刻退单套取奖励」。
        // 生效前归入「待生效」Tab，不可用于锁券。
        coupon.setEffectiveDelayDays(3);
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

    /** 锁券（下单占用），返回可抵扣金额（分）。 */
    @Transactional(rollbackFor = Exception.class)
    public long lock(Long userId, Long userCouponId, Long orderId, long orderAmount) {
        return lock(userId, userCouponId, String.valueOf(orderId), null, List.of(), null, orderAmount, null);
    }

    /** 内部 HTTP 契约锁券入口。 */
    @Transactional(rollbackFor = Exception.class)
    public CouponLockResult lockByOrderNo(Long userId,
                                          Long userCouponId,
                                          String orderNo,
                                          Long storeSubjectId,
                                          List<Long> productIds,
                                          String scene,
                                          Long orderAmount) {
        return lockByOrderNo(userId, userCouponId, orderNo, storeSubjectId, productIds,
                null, scene, orderAmount, null);
    }

    /** 锁券入口；items 优先用于计算仅适用商品行净额。 */
    @Transactional(rollbackFor = Exception.class)
    public CouponLockResult lockByOrderNo(Long userId,
                                          Long userCouponId,
                                          String orderNo,
                                          Long storeSubjectId,
                                          List<Long> productIds,
                                          List<CouponLockRequest.CouponItemAmount> items,
                                          String scene,
                                          Long orderAmount,
                                          Long applicableAmount) {
        long amount = orderAmount == null ? -1L : orderAmount;
        long discount = lock(userId, userCouponId, orderNo, storeSubjectId, productIds,
                items, scene, amount, applicableAmount);
        UserCoupon uc = userCouponMapper.selectByIdForUpdate(userCouponId);
        return new CouponLockResult(userCouponId, uc == null ? null : uc.getCouponId(), discount);
    }

    /** 内部 HTTP 契约锁券入口，applicableAmount 为仅适用商品行净额。 */
    @Transactional(rollbackFor = Exception.class)
    public CouponLockResult lockByOrderNo(Long userId,
                                          Long userCouponId,
                                          String orderNo,
                                          Long storeSubjectId,
                                          List<Long> productIds,
                                          String scene,
                                          Long orderAmount,
                                          Long applicableAmount) {
        return lockByOrderNo(userId, userCouponId, orderNo, storeSubjectId, productIds,
                null, scene, orderAmount, applicableAmount);
    }

    /** 锁券核心：行锁 + 条件更新，重复同订单调用幂等，其他订单锁定拒绝。 */
    @Transactional(rollbackFor = Exception.class)
    public long lock(Long userId,
                     Long userCouponId,
                     String orderNo,
                     Long storeSubjectId,
                     List<Long> productIds,
                     String scene,
                     long orderAmount) {
        return lock(userId, userCouponId, orderNo, storeSubjectId, productIds,
                null, scene, orderAmount, null);
    }

    /** 旧调用兼容入口。 */
    @Transactional(rollbackFor = Exception.class)
    public long lock(Long userId,
                     Long userCouponId,
                     String orderNo,
                     Long storeSubjectId,
                     List<Long> productIds,
                     String scene,
                     long orderAmount,
                     Long applicableAmount) {
        return lock(userId, userCouponId, orderNo, storeSubjectId, productIds,
                null, scene, orderAmount, applicableAmount);
    }

    /** 锁券核心：逐商品行净额优先，旧调用继续使用订单金额。 */
    @Transactional(rollbackFor = Exception.class)
    public long lock(Long userId,
                     Long userCouponId,
                     String orderNo,
                     Long storeSubjectId,
                     List<Long> productIds,
                     List<CouponLockRequest.CouponItemAmount> items,
                     String scene,
                     long orderAmount,
                     Long applicableAmount) {
        if (userId == null || userCouponId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "缺少 userId 或 userCouponId");
        }
        if (orderAmount < 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单金额不能为负数");
        }
        long orderId = OrderNoCodec.toLockOrderId(orderNo);
        UserCoupon uc = userCouponMapper.selectByIdForUpdate(userCouponId);
        if (uc == null || !userId.equals(uc.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "优惠券不存在");
        }
        Coupon coupon = couponMapper.selectById(uc.getCouponId());
        if (coupon == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "优惠券模板不存在");
        }

        List<Long> applicableProducts = parseLongList(coupon.getApplicableProductIds());
        long effectiveAmount = applicableAmount == null ? orderAmount : applicableAmount;
        if (items != null && !items.isEmpty()) {
            for (CouponLockRequest.CouponItemAmount item : items) {
                if (item != null && item.getAmount() != null && item.getAmount() < 0) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "商品行金额不能为负数");
                }
            }
            effectiveAmount = items.stream()
                    .filter(item -> item != null
                            && item.getProductId() != null
                            && item.getAmount() != null)
                    .filter(item -> applicableProducts.isEmpty()
                            || applicableProducts.contains(item.getProductId()))
                    .mapToLong(CouponLockRequest.CouponItemAmount::getAmount)
                    .sum();
        }
        if (effectiveAmount < 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "适用商品金额不能为负数");
        }
        if (effectiveAmount > orderAmount) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "适用商品金额不能超过订单金额");
        }

        long discount = calculateDiscount(coupon, uc, effectiveAmount, storeSubjectId,
                productIds, items, scene);
        if (LOCKED.equals(uc.getStatus())) {
            if (orderId == (uc.getLockOrderId() == null ? Long.MIN_VALUE : uc.getLockOrderId())) {
                return discount;
            }
            throw new BusinessException(ResultCode.BAD_REQUEST, "优惠券已锁定");
        }
        if (USED.equals(uc.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "优惠券已使用");
        }
        if (!UNUSED.equals(uc.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "优惠券不可用");
        }

        if (userCouponMapper.lockUnused(userCouponId, orderId) != 1) {
            // 行锁下正常不会走到；保留条件更新作为数据库级兜底。
            throw new BusinessException(ResultCode.BAD_REQUEST, "优惠券已被使用或锁定");
        }
        return discount;
    }

    private long calculateDiscount(Coupon coupon,
                                   UserCoupon holder,
                                   long orderAmount,
                                   Long storeSubjectId,
                                   List<Long> productIds,
                                   List<CouponLockRequest.CouponItemAmount> items,
                                   String scene) {
        LocalDateTime now = LocalDateTime.now();
        CouponValidity validity = resolveValidity(holder, coupon, now);
        if (!validity.usable()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "优惠券已过期或不可用");
        }
        if (!storeMatches(coupon.getApplicableStoreIds(), storeSubjectId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "优惠券不适用于当前门店");
        }
        if (!productMatches(coupon.getApplicableProductIds(), productIds, items)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "优惠券不适用于当前商品");
        }
        if (!sceneMatches(coupon.getScenes(), scene)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "优惠券不适用于当前场景");
        }
        if (!usageTimeMatches(coupon.getUsageTime(), now)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "优惠券当前不在使用时段");
        }
        long threshold = coupon.getThreshold() == null ? 0L : coupon.getThreshold();
        if (orderAmount < threshold) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单金额未达到优惠券使用门槛");
        }
        long amount = coupon.getAmount() == null ? 0L : coupon.getAmount();
        return Math.min(amount, orderAmount);
    }

    /** 返回用户券状态；重复核销按已核销处理，不重复写。 */
    @Transactional(rollbackFor = Exception.class)
    public Boolean consume(Long userId, Long userCouponId, String orderNo) {
        long orderId = OrderNoCodec.toLockOrderId(orderNo);
        UserCoupon uc = userCouponMapper.selectByIdForUpdate(userCouponId);
        if (uc == null || (userId != null && !userId.equals(uc.getUserId()))) {
            throw new BusinessException(ResultCode.NOT_FOUND, "优惠券不存在");
        }
        if (USED.equals(uc.getStatus())) {
            if (orderId == (uc.getLockOrderId() == null ? Long.MIN_VALUE : uc.getLockOrderId())) {
                return Boolean.TRUE;
            }
            throw new BusinessException(ResultCode.BAD_REQUEST, "优惠券不属于当前订单");
        }
        if (!LOCKED.equals(uc.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "优惠券尚未锁定");
        }
        if (userCouponMapper.consumeLocked(userCouponId, orderId) != 1) {
            UserCoupon latest = userCouponMapper.selectByIdForUpdate(userCouponId);
            boolean alreadyConsumed = latest != null
                    && USED.equals(latest.getStatus())
                    && orderId == (latest.getLockOrderId() == null ? Long.MIN_VALUE : latest.getLockOrderId())
                    && (userId == null || userId.equals(latest.getUserId()));
            if (alreadyConsumed) {
                return Boolean.TRUE;
            }
            throw new BusinessException(ResultCode.BAD_REQUEST, "优惠券不属于当前订单");
        }
        return Boolean.TRUE;
    }

    @Transactional(rollbackFor = Exception.class)
    public Boolean release(String orderNo, String reason) {
        long orderId = OrderNoCodec.toLockOrderId(orderNo);
        List<UserCoupon> list = userCouponMapper.selectList(new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getLockOrderId, orderId)
                .eq(UserCoupon::getStatus, LOCKED));
        if (list != null) {
            for (UserCoupon uc : list) {
                userCouponMapper.releaseLocked(uc.getId(), orderId);
            }
        }
        return Boolean.TRUE;
    }

    /** 支付后退款恢复，返回最终状态。 */
    @Transactional(rollbackFor = Exception.class)
    public String restoreAfterRefund(Long userId, Long userCouponId, String orderNo) {
        long orderId = OrderNoCodec.toLockOrderId(orderNo);
        UserCoupon uc = userCouponMapper.selectByIdForUpdate(userCouponId);
        if (uc == null || (userId != null && !userId.equals(uc.getUserId()))) {
            throw new BusinessException(ResultCode.NOT_FOUND, "优惠券不存在");
        }
        if (!USED.equals(uc.getStatus()) && !LOCKED.equals(uc.getStatus())) {
            return uc.getStatus();
        }
        if (uc.getLockOrderId() == null || uc.getLockOrderId() != orderId) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "优惠券不属于当前订单");
        }
        Coupon coupon = couponMapper.selectById(uc.getCouponId());
        LocalDateTime now = LocalDateTime.now();
        CouponValidity validity = resolveValidity(uc, coupon, now);
        String target = validity.usable() ? UNUSED : EXPIRED;
        if (UNUSED.equals(target) && hasActiveDuplicate(uc)) {
            target = EXPIRED;
            auditConflict(uc, now);
        }
        int affected = userCouponMapper.restoreAfterRefund(userCouponId, target);
        if (affected == 0) {
            UserCoupon latest = userCouponMapper.selectByIdForUpdate(userCouponId);
            return latest == null ? uc.getStatus() : latest.getStatus();
        }
        return target;
    }

    /** release 兼容旧 orderId 调用；内部接口统一走 orderNo。 */
    @Transactional(rollbackFor = Exception.class)
    public void releaseByOrder(Long orderId) {
        release(String.valueOf(orderId), "订单取消或超时");
    }

    private boolean hasActiveDuplicate(UserCoupon current) {
        List<UserCoupon> active = userCouponMapper.selectList(new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getUserId, current.getUserId())
                .eq(UserCoupon::getCouponId, current.getCouponId())
                .in(UserCoupon::getStatus, List.of(UNUSED, LOCKED)));
        if (active == null) {
            return false;
        }
        return active.stream().anyMatch(uc -> uc != null && !Objects.equals(uc.getId(), current.getId()));
    }

    private void auditConflict(UserCoupon uc, LocalDateTime now) {
        AuditLog log = new AuditLog();
        log.setOperator("system");
        log.setModule("COUPON");
        log.setAction("REFUND_RESTORE_CONFLICT_EXPIRED");
        log.setTarget(String.valueOf(uc.getId()));
        log.setBeforeValue(uc.getStatus());
        log.setAfterValue(EXPIRED);
        log.setReason("同模板已有active新券");
        log.setIp("internal");
        log.setCreateTime(now);
        log.setUpdateTime(now);
        int inserted = auditLogMapper.insert(log);
        if (inserted != 1) {
            throw new BusinessException(ResultCode.ERROR, "优惠券退款恢复审计写入失败");
        }
    }

    private boolean storeMatches(String raw, Long storeSubjectId) {
        List<Long> values = parseLongList(raw);
        return values.isEmpty() || (storeSubjectId != null && values.contains(storeSubjectId));
    }

    private boolean productMatches(String raw,
                                   List<Long> productIds,
                                   List<CouponLockRequest.CouponItemAmount> items) {
        List<Long> applicable = parseLongList(raw);
        if (applicable.isEmpty()) {
            return true;
        }
        if (items != null && !items.isEmpty()) {
            return items.stream()
                    .filter(Objects::nonNull)
                    .map(CouponLockRequest.CouponItemAmount::getProductId)
                    .filter(Objects::nonNull)
                    .anyMatch(applicable::contains);
        }
        if (productIds == null || productIds.isEmpty()) {
            return false;
        }
        return productIds.stream().filter(Objects::nonNull).anyMatch(applicable::contains);
    }

    private boolean sceneMatches(String raw, String scene) {
        List<String> configured = parseStringList(raw);
        if (configured.isEmpty()) {
            return true;
        }
        if (scene == null || scene.isBlank()) {
            return false;
        }
        String requested = normalizeScene(scene);
        return configured.stream().map(this::normalizeScene).anyMatch(requested::equals);
    }

    private String normalizeScene(String scene) {
        String value = scene == null ? "" : scene.trim();
        return switch (value) {
            case "买单", "buy" -> "buy";
            case "堂食(门店就餐)", "堂食（门店就餐）", "dinein" -> "dinein";
            case "堂食(打包外带)", "堂食（打包外带）", "pickup" -> "pickup";
            default -> value.toLowerCase();
        };
    }

    private boolean usageTimeMatches(String raw, LocalDateTime now) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        String[] parts = raw.trim().split("~");
        if (parts.length != 2) {
            return false;
        }
        try {
            LocalTime start = parseTime(parts[0]);
            LocalTime end = parseTime(parts[1]);
            LocalTime current = now.toLocalTime();
            if (start.equals(end)) {
                return true;
            }
            if (start.isBefore(end)) {
                return !current.isBefore(start) && !current.isAfter(end);
            }
            return !current.isBefore(start) || !current.isAfter(end);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private LocalTime parseTime(String value) {
        String text = value.trim();
        if (text.matches("\\d{2}:\\d{2}")) {
            text = text + ":00";
        }
        return LocalTime.parse(text);
    }

    private List<Long> parseLongList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node.isNull() || node.isMissingNode()) {
                return List.of();
            }
            if (node.isArray()) {
                List<Long> result = new ArrayList<>();
                for (JsonNode item : node) {
                    if (item != null && !item.isNull()) {
                        result.add(item.asLong());
                    }
                }
                return result;
            }
        } catch (Exception ignored) {
            // 兼容老数据里的 1,2,3 文本格式。
        }
        List<Long> result = new ArrayList<>();
        for (String item : raw.split(",")) {
            if (!item.isBlank()) {
                result.add(Long.valueOf(item.trim()));
            }
        }
        return result;
    }

    private List<String> parseStringList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node.isNull() || node.isMissingNode()) {
                return List.of();
            }
            if (node.isArray()) {
                List<String> result = new ArrayList<>();
                node.forEach(item -> result.add(item.asText()));
                return result;
            }
        } catch (Exception ignored) {
            // 老数据是中文文本，继续按分隔符解析。
        }
        return List.of(raw.split("[,，、]"));
    }

    public record CouponLockResult(Long userCouponId, Long couponId, long discountAmount) {
    }
}
