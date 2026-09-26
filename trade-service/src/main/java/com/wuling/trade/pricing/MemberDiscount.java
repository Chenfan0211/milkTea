package com.wuling.trade.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 会员折扣解析（与小程序端 utils/member-level.js 的 parseDiscount 逐条对齐）。
 *
 * <p><b>为什么必须两端同口径</b>：前端展示的会员价与后端下单实收金额必须一致。
 * 若解析规则不同（例如前端认「8折」、后端认「0.8」），会出现
 * 「小程序显示 14.4 元、实际扣 16 元」这类客诉，且对账口径也会错乱。
 *
 * <p>支持的写法（与前端一致）：
 * <ul>
 *   <li>整数百分比（当前规范）：{@code 80} / {@code 80%} → 0.8；{@code 1} → 0.01</li>
 *   <li>中文折扣（历史兼容）：{@code 8折} / {@code 7.5折} → 0.8 / 0.75</li>
 *   <li>小数比率（历史兼容）：{@code 0.8} → 0.8</li>
 *   <li>空值 / 脏数据 / 越界百分比 / 超出 (0,10] 折区间 → 1.0（不打折，绝不误打折）</li>
 * </ul>
 */
public final class MemberDiscount {

    /**
     * 中文折扣：捕获符号与数字，如「8折」→ 8、「7.5折」→ 7.5。
     *
     * <p>符号必须一起捕获（{@code [-+]?}）：若只匹配 {@code \d+}，
     * 「-3折」会被解析成「3折」= 0.3 —— 一个本应拒绝的脏数据反而变成 3 折，
     * 方向恰恰是「多打折」，属于会让平台少收钱的错误。同类问题见下面
     * {@link #parse} 里的区间校验。
     */
    private static final Pattern ZHE_PATTERN = Pattern.compile("([-+]?\\d+(?:\\.\\d+)?)\\s*折");

    /** 新折扣规范：整数百分比，如「80%」→ 0.8。 */
    private static final Pattern PERCENT_PATTERN = Pattern.compile("([-+]?\\d+(?:\\.\\d+)?)\\s*%");

    private MemberDiscount() {
    }

    /**
     * 把等级折扣配置解析为乘数。
     *
     * @param discountText 等级表 discount 字段原文，如「8折」
     * @return 折扣乘数，落在 (0,1]；无法识别时返回 1.0（不打折）
     */
    public static BigDecimal parse(String discountText) {
        if (discountText == null) {
            return BigDecimal.ONE;
        }
        String text = discountText.trim();
        if (text.isEmpty()) {
            return BigDecimal.ONE;
        }

        Matcher matcher = ZHE_PATTERN.matcher(text);
        if (matcher.find()) {
            BigDecimal zhe = new BigDecimal(matcher.group(1));
            // 折扣应落在 (0,10] 折区间；超出视为脏数据，按不打折兜底
            if (zhe.compareTo(BigDecimal.ZERO) > 0 && zhe.compareTo(BigDecimal.TEN) <= 0) {
                return zhe.divide(BigDecimal.TEN, 4, RoundingMode.HALF_UP);
            }
            return BigDecimal.ONE;
        }

        Matcher percentMatcher = PERCENT_PATTERN.matcher(text);
        if (percentMatcher.find()) {
            return percentToMultiplier(new BigDecimal(percentMatcher.group(1)));
        }

        try {
            BigDecimal value = new BigDecimal(text);

            // 历史小数比例：0.8 表示 0.8。纯整数 1 按新规范表示 1%，不再按 100% 处理。
            if (value.compareTo(BigDecimal.ZERO) > 0 && value.compareTo(BigDecimal.ONE) < 0) {
                return value;
            }

            // 新规范：1~100 的整数百分比。拒绝 1.5 等旧规范中有歧义的值。
            if (value.stripTrailingZeros().scale() <= 0
                    && value.compareTo(BigDecimal.ONE) >= 0
                    && value.compareTo(BigDecimal.valueOf(100)) <= 0) {
                return percentToMultiplier(value);
            }
        } catch (NumberFormatException ignored) {
            // 非数字且不含「折/百分号」：按不打折处理
        }
        return BigDecimal.ONE;
    }

    /** 百分比值转乘数；越界统一按不打折处理。 */
    private static BigDecimal percentToMultiplier(BigDecimal percent) {
        if (percent.compareTo(BigDecimal.ZERO) > 0
                && percent.compareTo(BigDecimal.valueOf(100)) <= 0) {
            return percent.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        }
        return BigDecimal.ONE;
    }

    /**
     * 会员价 = 原价 × 折扣，四舍五入到「分」。
     *
     * <p>用 BigDecimal 而非 double：金额为整数分，浮点乘法会在
     * 7.5 折这类场景产生 0.005 级别的舍入漂移，长期对账会累积误差。
     *
     * @param originalPrice 商品原价（分）。null / 负数按 0 处理
     * @param discount      折扣乘数（见 {@link #parse}）
     * @return 会员价（分）
     */
    public static long memberPrice(Long originalPrice, BigDecimal discount) {
        long base = originalPrice == null || originalPrice < 0 ? 0L : originalPrice;
        if (discount == null) {
            return base;
        }
        return BigDecimal.valueOf(base)
                .multiply(discount)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }
}