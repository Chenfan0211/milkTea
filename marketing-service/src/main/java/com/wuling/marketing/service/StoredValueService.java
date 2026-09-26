package com.wuling.marketing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.common.api.PageResult;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.marketing.dto.StoredValuePackageDTO;
import com.wuling.marketing.entity.Coupon;
import com.wuling.marketing.entity.StoredValueOrder;
import com.wuling.marketing.entity.StoredValuePackage;
import com.wuling.marketing.entity.StoredValuePackageCoupon;
import com.wuling.marketing.mapper.CouponMapper;
import com.wuling.marketing.mapper.StoredValueOrderMapper;
import com.wuling.marketing.mapper.StoredValuePackageCouponMapper;
import com.wuling.marketing.mapper.StoredValuePackageMapper;
import com.wuling.user.entity.AppUser;
import com.wuling.user.mapper.AppUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 储值充值。
 *
 * <p>对齐方案：储值购买时不分账，核销消费时才分账（预收资金不提前分配）。
 *
 * <p><b>支付生命周期（第 15 期改造）</b>：
 * 原实现 {@code recharge()} 一次性完成「建单 + 直接置 PAID + 入账」，
 * 那是 mock 时代的一步到位写法。接入微信支付后必须拆开：
 * <ol>
 *   <li>{@link #createOrder} —— 建单（UNPAID），返回订单号供支付渠道下单；</li>
 *   <li>{@link #markPaid} —— 由<b>支付回调</b>调用，幂等地置 PAID 并入账。</li>
 * </ol>
 * 关键：入账只能由回调触发，绝不能由前端 {@code requestPayment} 的成功回调触发 ——
 * 前端回调不代表资金到账。
 *
 * <p><b>幂等</b>：{@link #markPaid} 以「UNPAID → PAID」的条件更新作为闸门，
 * 微信重复回调不会重复入账。
 */
@Service
public class StoredValueService {

    private static final Logger log = LoggerFactory.getLogger(StoredValueService.class);

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 支付状态：待支付 */
    public static final String PAY_UNPAID = "UNPAID";
    /** 支付状态：已支付 */
    public static final String PAY_PAID = "PAID";

    private final StoredValuePackageMapper packageMapper;
    private final StoredValuePackageCouponMapper packageCouponMapper;
    private final StoredValueOrderMapper orderMapper;
    private final CouponMapper couponMapper;
    private final AppUserMapper appUserMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StoredValueService(StoredValuePackageMapper packageMapper,
                              StoredValuePackageCouponMapper packageCouponMapper,
                              StoredValueOrderMapper orderMapper,
                              CouponMapper couponMapper,
                              AppUserMapper appUserMapper) {
        this.packageMapper = packageMapper;
        this.packageCouponMapper = packageCouponMapper;
        this.orderMapper = orderMapper;
        this.couponMapper = couponMapper;
        this.appUserMapper = appUserMapper;
    }

    // ============================================================
    // 套餐查询
    // ============================================================

    /**
     * 启用中的储值套餐（含赠券与使用说明）。
     *
     * <p>返回 DTO 而非实体：赠券在关联表、说明在 JSON 列，两者都不在实体字段中，
     * 直接下发实体会让小程序端赠券区恒为空。
     */
    public List<StoredValuePackageDTO> listPackages() {
        List<StoredValuePackage> packages = packageMapper.selectList(
                new LambdaQueryWrapper<StoredValuePackage>()
                        .eq(StoredValuePackage::getStatus, "enabled")
                        .orderByAsc(StoredValuePackage::getAmount));
        List<StoredValuePackageDTO> result = new ArrayList<>(packages.size());
        for (StoredValuePackage pkg : packages) {
            result.add(toDTO(pkg));
        }
        return result;
    }

    /** 单个套餐（含赠券与说明）；不存在或未启用时抛业务异常 */
    public StoredValuePackageDTO requireEnabledPackage(Long packageId) {
        StoredValuePackage pkg = packageMapper.selectById(packageId);
        if (pkg == null || !"enabled".equals(pkg.getStatus())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "储值套餐不存在或已下架");
        }
        return toDTO(pkg);
    }

    private StoredValuePackageDTO toDTO(StoredValuePackage pkg) {
        StoredValuePackageDTO dto = new StoredValuePackageDTO();
        dto.setId(pkg.getId());
        dto.setCode(pkg.getCode());
        dto.setName(pkg.getName());
        dto.setAmount(pkg.getAmount());
        dto.setStatus(pkg.getStatus());
        dto.setCoupons(loadGiftCoupons(pkg.getId()));
        dto.setUsageParagraphs(parseParagraphs(pkg.getUsageParagraphs()));
        return dto;
    }

    /** 赠券明细：关联表 JOIN coupon，解析出面额与展示文案 */
    private List<StoredValuePackageDTO.GiftCoupon> loadGiftCoupons(Long packageId) {
        List<StoredValuePackageCoupon> links = packageCouponMapper.selectList(
                new LambdaQueryWrapper<StoredValuePackageCoupon>()
                        .eq(StoredValuePackageCoupon::getPackageId, packageId));
        List<StoredValuePackageDTO.GiftCoupon> result = new ArrayList<>(links.size());
        for (StoredValuePackageCoupon link : links) {
            Coupon coupon = couponMapper.selectById(link.getCouponId());
            if (coupon == null) {
                // 券模板被删除时跳过该项，不影响整个套餐展示
                continue;
            }
            StoredValuePackageDTO.GiftCoupon item = new StoredValuePackageDTO.GiftCoupon();
            item.setCouponId(coupon.getId());
            item.setAmount(coupon.getAmount());
            item.setQuantity(link.getCount() == null ? 0 : link.getCount());
            item.setDescription(StringUtils.hasText(coupon.getName())
                    ? coupon.getName()
                    : "储值赠送券");
            result.add(item);
        }
        return result;
    }

    /** JSON 字符串数组 -> List；解析失败返回空列表，由前端回落默认文案 */
    private List<String> parseParagraphs(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("储值套餐使用说明解析失败，按空处理: {}", e.getMessage());
            return List.of();
        }
    }

    // ============================================================
    // 充值：建单 / 入账
    // ============================================================

    /**
     * 创建储值订单（UNPAID），不改变余额。
     *
     * <p>金额以<b>服务端套餐配置</b>为准，不接受前端传入 —— 否则可被篡改金额。
     *
     * @param userId    当前登录用户
     * @param packageId 套餐 ID
     * @return 待支付的储值订单
     */
    @Transactional(rollbackFor = Exception.class)
    public StoredValueOrder createOrder(Long userId, Long packageId) {
        StoredValuePackageDTO pkg = requireEnabledPackage(packageId);
        AppUser user = appUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }

        StoredValueOrder order = new StoredValueOrder();
        order.setOrderNo(nextNo("CZ"));
        order.setUserId(userId);
        order.setPackageId(packageId);
        order.setAmount(pkg.getAmount());
        order.setPayStatus(PAY_UNPAID);
        orderMapper.insert(order);
        log.info("stored value order created orderNo={} userId={} amount={}",
                order.getOrderNo(), userId, pkg.getAmount());
        return order;
    }

    /** 按订单号查询储值订单；不存在时抛业务异常 */
    public StoredValueOrder requireByOrderNo(String orderNo) {
        StoredValueOrder order = orderMapper.selectOne(
                new LambdaQueryWrapper<StoredValueOrder>()
                        .eq(StoredValueOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "储值订单不存在");
        }
        return order;
    }

    /**
     * 标记订单已支付并给用户入账（由支付回调调用）。
     *
     * <p><b>幂等闸门</b>：以「pay_status = UNPAID」为条件做原子更新。
     * 更新影响 0 行说明已被处理过，直接返回，不再入账 ——
     * 微信回调会重试，缺此闸门会导致重复充值。
     *
     * <p><b>金额校验</b>：回调金额必须与订单金额一致，否则拒绝入账。
     * 不一致意味着回调可能被伪造或串单，入账即造成资金损失。
     *
     * @param orderNo       储值订单号
     * @param transactionId 微信支付交易号（mock 通道传渠道标识）
     * @param payerOpenid   支付者 openid（退款原路退回必需），可为 null
     * @param callbackAmount 回调金额（分）；为 null 时跳过金额校验（mock 路径已自行校验）
     * @return 入账后的订单；已被处理过时返回当前订单
     */
    @Transactional(rollbackFor = Exception.class)
    public StoredValueOrder markPaid(String orderNo, String transactionId,
                                     String payerOpenid, Long callbackAmount) {
        StoredValueOrder order = requireByOrderNo(orderNo);

        if (PAY_PAID.equals(order.getPayStatus())) {
            log.info("duplicate stored value callback ignored, orderNo={}", orderNo);
            return order;
        }

        if (callbackAmount != null && !order.getAmount().equals(callbackAmount)) {
            String detail = "储值订单金额=" + order.getAmount() + " 回调金额=" + callbackAmount
                    + " 微信交易号=" + transactionId;
            log.error("储值回调金额不一致，拒绝入账 orderNo={} {}", orderNo, detail);
            throw new BusinessException(ResultCode.BAD_REQUEST, "回调金额与订单金额不一致");
        }

        // 原子闸门：仅当仍为 UNPAID 时才置 PAID，避免并发/重试重复入账
        StoredValueOrder update = new StoredValueOrder();
        update.setId(order.getId());
        update.setPayStatus(PAY_PAID);
        update.setTransactionId(transactionId);
        update.setPayerOpenid(payerOpenid);
        int affected = orderMapper.update(update,
                new LambdaQueryWrapper<StoredValueOrder>()
                        .eq(StoredValueOrder::getId, order.getId())
                        .eq(StoredValueOrder::getPayStatus, PAY_UNPAID));
        if (affected == 0) {
            log.info("stored value order already settled, skip orderNo={}", orderNo);
            return requireByOrderNo(orderNo);
        }

        // 原子入账，避免「读-改-写」丢失更新
        appUserMapper.addBalance(order.getUserId(), order.getAmount());
        log.info("stored value recharge ok orderNo={} userId={} amount={}",
                orderNo, order.getUserId(), order.getAmount());
        return requireByOrderNo(orderNo);
    }

    /**
     * 储值余额支付：从余额扣款，用于「点单订单」的支付。
     *
     * <p><b>为什么必须走服务端扣款</b>：前端曾用本地存储直接改余额
     * （{@code submitWithStoredValue} 只改本地 profile），既不产生支付记录，
     * 也无法防并发超扣。扣款是一次资金动作，必须在服务端以 DB 行为准。
     *
     * <p><b>并发与透支防护</b>：{@link AppUserMapper#addBalance} 的 SQL 条件带
     * {@code balance + delta >= 0}，扣到不足时返回 0 —— 这里据此抛「余额不足」，
     * 而不是当成系统错误。调用方（trade）在一个事务内完成
     * 「扣余额 + 置订单已支付 + 写支付单」，任一步失败整体回滚。
     *
     * @param userId  用户 ID
     * @param amount  扣款金额（分），必须 &gt; 0
     * @param bizNo   业务单号（点单订单号），仅用于日志与对账追溯
     * @throws BusinessException 余额不足 / 参数非法 / 用户不存在
     */
    @Transactional(rollbackFor = Exception.class)
    public void payWithBalance(Long userId, long amount, String bizNo) {
        if (userId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户不存在");
        }
        if (amount <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "扣款金额必须大于 0");
        }
        int affected = appUserMapper.addBalance(userId, -amount);
        if (affected == 0) {
            // 0 有两种含义：用户不存在 / 余额不足。对支付场景而言
            // 二者都必须拒绝扣款，且提示以「余额不足」为主（更可能是用户可自解的原因）。
            log.warn("储值余额扣款失败（余额不足或用户不存在）userId={} amount={} bizNo={}",
                    userId, amount, bizNo);
            throw new BusinessException(ResultCode.BAD_REQUEST, "储值余额不足，请先充值");
        }
        log.info("储值余额支付成功 userId={} amount={} bizNo={}", userId, amount, bizNo);
    }

    /**
     * 储值余额退回（仅用于「余额支付的订单」退款/取消）。
     *
     * <p><b>与充值退款的区别（重要）</b>：
     * <ul>
     *   <li>充值（CZ 单）<b>不可退</b> —— 本方法绝不是充值退款；
     *       它只回冲「用余额买商品」时扣掉的那笔钱；</li>
     *   <li>余额支付的点单订单可退/可取消，资金原路退回<b>储值余额</b>
     *       （不是退回微信），故走本方法而不是微信退款。</li>
     * </ul>
     *
     * <p><b>幂等</b>：由调用方（trade 的退款流程）保证一单只回冲一次 ——
     * order 的 REFUNDED 状态流转本身即为闸门。本方法不做额外判重，
     * 避免在两处维护同一份幂等逻辑。
     *
     * @param userId 用户 ID
     * @param amount 退回金额（分），必须 &gt; 0
     * @param bizNo  业务单号（点单订单号），仅用于日志与对账追溯
     */
    @Transactional(rollbackFor = Exception.class)
    public void refundToBalance(Long userId, long amount, String bizNo) {
        if (userId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户不存在");
        }
        if (amount <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "退回金额必须大于 0");
        }
        int affected = appUserMapper.addBalance(userId, amount);
        if (affected == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在，无法退回储值余额");
        }
        log.info("储值余额退回成功 userId={} amount={} bizNo={}", userId, amount, bizNo);
    }

    /** 我的储值订单（分页）；status 由 pay_status 推导下发。 */
    public PageResult<StoredValueOrder> myOrders(Long userId, long current, long size) {
        Page<StoredValueOrder> page = orderMapper.selectPage(
                new Page<>(current, size),
                new LambdaQueryWrapper<StoredValueOrder>()
                        .eq(StoredValueOrder::getUserId, userId)
                        .orderByDesc(StoredValueOrder::getId));
        List<StoredValueOrder> records = page.getRecords().stream()
                .map(StoredValueService::withDerivedStatus)
                .toList();
        return PageResult.of(records, page.getCurrent(), page.getSize(), page.getTotal());
    }

    /** 单个订单视图（供小程序支付后主动查单） */
    public StoredValueOrder orderView(Long userId, String orderNo) {
        StoredValueOrder order = requireByOrderNo(orderNo);
        if (!order.getUserId().equals(userId)) {
            // 不区分「不存在」与「非本人」，避免探测他人订单号
            throw new BusinessException(ResultCode.NOT_FOUND, "储值订单不存在");
        }
        return withDerivedStatus(order);
    }

    /**
     * 储值订单状态推导：业务上只有「待支付」与「已完成」两种（充值成功即入账）。
     * pay_status = PAID -> COMPLETED，其余（UNPAID）-> CREATED。
     */
    private static StoredValueOrder withDerivedStatus(StoredValueOrder order) {
        boolean paid = PAY_PAID.equalsIgnoreCase(order.getPayStatus());
        order.setStatus(paid ? "COMPLETED" : "CREATED");
        return order;
    }

    private String nextNo(String prefix) {
        return prefix + LocalDateTime.now().format(NO_FMT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }
}
