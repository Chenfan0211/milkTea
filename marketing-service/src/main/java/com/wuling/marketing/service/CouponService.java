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
     * 我的优惠券（按 Tab 筛选）。
     *
     * <p><b>四态口径（唯一）</b>：未使用 / 待生效 / 已过期 / 已使用，由 {@link #deriveTab} 统一推导，
     * 一张券只会出现在一个 Tab：
     * <ul>
     *   <li>未使用：仍是 UNUSED 且已生效未过期，<b>外加被订单占用（LOCKED）的券</b>（前端标注「订单占用中」）；</li>
     *   <li>待生效：仍是 UNUSED 但生效时间未到；</li>
     *   <li>已过期：库表 EXPIRED，<b>或仍是 UNUSED 但已过有效期</b>；</li>
     *   <li>已使用：库表 USED（含之后过了有效期的 —— 终态优先）。</li>
     * </ul>
     *
     * <p><b>为什么「已过期」必须现算</b>：DAYS 类型券（领取后 N 天）库里没有过期时间落点，
     * 也没有任务把过期券写成 EXPIRED；若只按 status 查，「已过期」Tab 永远为空，
     * 而过期未用的券又会被「未使用」的可用性过滤掉 —— 用户会看到券凭空消失。
     *
     * <p>展示 ID 始终使用 user_coupon.id。
     */
    public List<UserCouponView> myCoupons(Long userId, String status) {
        String tab = normalizeTab(status);

        LambdaQueryWrapper<UserCoupon> query = new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getUserId, userId)
                .orderByDesc(UserCoupon::getId);
        // 只查可能与目标 Tab 相关的库表状态；最终归类一律由 deriveTab 判定。
        switch (tab) {
            case USED -> query.eq(UserCoupon::getStatus, USED);
            // 已过期：库表 EXPIRED +「仍是 UNUSED 但已过有效期」（后者没有落库，必须现算）
            case EXPIRED -> query.in(UserCoupon::getStatus, List.of(EXPIRED, UNUSED));
            // 待生效：仍是 UNUSED，按生效时间区分
            case PENDING -> query.eq(UserCoupon::getStatus, UNUSED);
            // 未使用：可用券 + 被订单占用的券（占用中的券也要让用户看到，前端标注「订单占用中」）
            default -> query.in(UserCoupon::getStatus, List.of(UNUSED, LOCKED));
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
            // 四态互斥：只保留归类等于请求 Tab 的券
            if (!tab.equals(deriveTab(userCoupon, validity))) {
                continue;
            }
            UserCouponView view = toView(userCoupon, coupon, validity);
            view.setExpired(EXPIRED.equals(tab));
            // usable =「此刻能不能拿去下单」：只有「未使用 Tab 里的 UNUSED 券」为 true；
            // 订单占用中 / 待生效 / 已使用 / 已过期都是 false
            //（前端券张数与下单页选券都依赖这个语义）。
            view.setUsable(UNUSED.equals(tab) && !LOCKED.equals(userCoupon.getStatus()));
            result.add(view);
        }
        return result;
    }

    /** Tab 归一：不传/未知值一律按「未使用」处理（兼容旧调用与「我的」页计数口径）。 */
    private String normalizeTab(String status) {
        if (status == null || status.isBlank()) {
            return UNUSED;
        }
        return switch (status.trim().toUpperCase()) {
            case PENDING, USED, EXPIRED -> status.trim().toUpperCase();
            // LOCKED 与未知值都归到「未使用」口径（小程序没有独立的「锁定」Tab）
            default -> UNUSED;
        };
    }

    /**
     * 优惠券四态推导（列表与计数共用的唯一口径）。
     *
     * <p>库表 status 只有 UNUSED / LOCKED / USED / EXPIRED，而「过期」对 DAYS 类型券没有落点，
     * 因此这里把「状态 + 有效性」合成用户看到的四个 Tab：
     * 已使用（终态优先）→ 已过期 → 订单占用/未使用 → 待生效。
     *
     * @return 四态之一；未知状态返回 null（不展示）
     */
    private String deriveTab(UserCoupon userCoupon, CouponValidity validity) {
        String status = userCoupon.getStatus();
        if (USED.equals(status)) {
            // 终态：用过就一直算「已使用」，之后过了有效期也不改归类
            return USED;
        }
        if (EXPIRED.equals(status)) {
            return EXPIRED;
        }
        if (LOCKED.equals(status)) {
            // 下单占用中：仍在「未使用」展示（前端标注「订单占用中」），但不可用
            return UNUSED;
        }
        if (!UNUSED.equals(status)) {
            return null;
        }
        if (!validity.started()) {
            return PENDING;
        }
        return validity.usable() ? UNUSED : EXPIRED;
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

    /**
     * 返回用户券状态；重复核销按已核销处理，不重复写。
     *
     * <p><b>userId 必填（fail-closed）</b>：用户券与用户是绑定关系，
     * 状态流转必须带「这是谁的券」；缺 userId 时若跳过归属校验，
     * 就给了「凭 userCouponId 操作他人券」的口子。所有调用方（支付核销 MQ 事件、
     * 内部接口）都会带上订单归属的 userId。
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean consume(Long userId, Long userCouponId, String orderNo) {
        if (userId == null || userCouponId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "缺少 userId 或 userCouponId");
        }
        long orderId = OrderNoCodec.toLockOrderId(orderNo);
        UserCoupon uc = userCouponMapper.selectByIdForUpdate(userCouponId);
        if (uc == null || !userId.equals(uc.getUserId())) {
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
                    && userId.equals(latest.getUserId());
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
        if (userId == null || userCouponId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "缺少 userId 或 userCouponId");
        }
        long orderId = OrderNoCodec.toLockOrderId(orderNo);
        UserCoupon uc = userCouponMapper.selectByIdForUpdate(userCouponId);
        if (uc == null || !userId.equals(uc.getUserId())) {
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

    /**
     * 把「已过有效期但仍是 UNUSED」的券归档为 EXPIRED（分批、幂等）。
     *
     * <p><b>列表不依赖它</b>：DAYS 类型券在库里没有过期时间落点，「已过期」Tab 由
     * {@link #deriveTab} 现算保证永远正确；归档只为状态可索引、可统计、可对账 ——
     * 本任务停摆不会让用户看到错分类，这是刻意设计。
     *
     * @param batchSize 单批扫描上限
     * @return 本批实际归档张数
     */
    @Transactional(rollbackFor = Exception.class)
    public int archiveOverdueUnused(int batchSize) {
        int size = batchSize <= 0 ? 200 : batchSize;
        List<Long> ids = userCouponMapper.selectOverdueUnusedIds(size);
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int archived = 0;
        for (Long id : ids) {
            archived += userCouponMapper.expireOverdueUnused(id);
        }
        if (archived > 0 && auditLogMapper != null) {
            AuditLog log = new AuditLog();
            log.setOperator("system");
            log.setModule("COUPON");
            log.setAction("EXPIRE_SWEEP");
            log.setTarget("batch:" + archived);
            log.setBeforeValue(UNUSED);
            log.setAfterValue(EXPIRED);
            log.setReason("过期未用券批量归档（仅用于可统计，展示层不依赖）");
            log.setIp("internal");
            log.setCreateTime(LocalDateTime.now());
            log.setUpdateTime(LocalDateTime.now());
            auditLogMapper.insert(log);
        }
        return archived;
    }    private boolean hasActiveDuplicate(UserCoupon current) {
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
