package com.wuling.trade.pricing;

import com.wuling.trade.port.MemberLevelPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 会员价计算服务（第 15 期）。
 *
 * <p><b>职责边界</b>：本类只负责「按商品原价 × 等级折扣算出会员价」，
 * 并把前端传入的金额与服务端结果做校验；不碰订单实体、不写库。
 * 这样计价规则可以被单测直接覆盖（见 {@code MemberPricingServiceTest}），
 * 也避免把规则散落在 {@code OrderService} 的下单主流程里。
 *
 * <p><b>等级来源（重要）</b>：以<b>服务端记录的等级为准</b>。
 * 请求体里的 {@code vipLevel} 只用于交叉校验：
 * <ul>
 *   <li>一致 → 正常计价；</li>
 *   <li>不一致 → <b>仍按服务端等级计价</b>，并记 WARN 日志。
 *       若直接采信前端传入的等级，用户改包成 Lv3 即可拿 6 折。</li>
 * </ul>
 *
 * <p><b>折扣基准价</b>：商品<b>原价</b>（{@code product.original_price}），
 * 与小程序端 product-card / spec-sheet 的 {@code calcMemberPrice} 同口径。
 * 注意 {@code product.price} 本身就是「会员价基数」，不是打折前的门市价。
 */
@Service
public class MemberPricingService {

    private static final Logger log = LoggerFactory.getLogger(MemberPricingService.class);

    private final MemberLevelPort memberLevelPort;

    public MemberPricingService(MemberLevelPort memberLevelPort) {
        this.memberLevelPort = memberLevelPort;
    }

    /**
     * 解析本次下单生效的等级代码。
     *
     * <p>服务端记录优先；服务端查不到时（例如端口故障或未设等级）
     * 才回落到前端传入值，保证「端口不可用不阻塞下单」。
     *
     * @param userId           取自 JWT 的用户 ID
     * @param clientVipLevel   前端传入的等级代码，可为 null
     * @return 生效的等级代码；均无则返回 null（按不打折处理）
     */
    public String resolveLevelCode(Long userId, String clientVipLevel) {
        String serverLevel = null;
        try {
            serverLevel = memberLevelPort.findUserLevelCode(userId);
        } catch (Exception e) {
            log.warn("查询服务端会员等级失败，回落客户端等级 userId={} err={}", userId, e.getMessage());
        }

        if (serverLevel != null && !serverLevel.isBlank()) {
            if (clientVipLevel != null && !clientVipLevel.isBlank()
                    && !serverLevel.equals(clientVipLevel)) {
                // 客户端等级与服务端不符：只记日志、不采信客户端，便于发现改包/前端 bug
                log.warn("客户端会员等级与服务端不一致，已按服务端计价 userId={} 客户端={} 服务端={}",
                        userId, clientVipLevel, serverLevel);
            }
            return serverLevel;
        }
        return clientVipLevel == null || clientVipLevel.isBlank() ? null : clientVipLevel;
    }

    /**
     * 计算会员价（分）。
     *
     * @param originalPrice 商品原价（分）
     * @param levelCode     生效等级代码；null / 未知等级 → 不打折
     * @return 会员价（分），不小于 0
     */
    public long memberPrice(Long originalPrice, String levelCode) {
        BigDecimal discount = discountOf(levelCode);
        return MemberDiscount.memberPrice(originalPrice, discount);
    }

    /** 查询等级折扣乘数；等级不存在或查询失败时返回 1.0（不打折）。 */
    public BigDecimal discountOf(String levelCode) {
        if (levelCode == null || levelCode.isBlank()) {
            return BigDecimal.ONE;
        }
        try {
            return MemberDiscount.parse(memberLevelPort.findLevelDiscount(levelCode));
        } catch (Exception e) {
            log.warn("查询等级折扣失败，按不打折处理 levelCode={} err={}", levelCode, e.getMessage());
            return BigDecimal.ONE;
        }
    }

    /**
     * 校验前端提交的金额与服务端计算结果。
     *
     * <p>无论校验结果如何，调用方都应使用 {@link MemberPriceCheck#serverAmount()} 计价。
     */
    public MemberPriceCheck verify(Long clientAmount, long serverAmount) {
        MemberPriceCheck check = MemberPriceCheck.of(clientAmount, serverAmount);
        if (!check.correct() && clientAmount != null) {
            // 金额不一致通常是前端与服务端折扣口径漂移，必须留痕
            log.warn("会员价校验不一致 客户端={}分 服务端={}分 原因={}",
                    clientAmount, serverAmount, check.reason());
        }
        return check;
    }
}