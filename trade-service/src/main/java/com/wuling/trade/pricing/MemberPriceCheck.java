package com.wuling.trade.pricing;

/**
 * 会员价校验结果。
 *
 * <p><b>为什么要有这个类型</b>：用户要求「后端不要相信前端的数据，
 * 需要做校验告诉前端金额是否正确」。因此下单响应里必须能携带
 * 「你算的价对不对、差在哪」，而不是只回一个静默的最终金额 ——
 * 否则前端无法发现自己与服务端口径漂移（例如折扣解析规则被改动）。
 *
 * <p><b>金额一律以服务端为准</b>：无论校验是否通过，下单用的是
 * {@link #serverAmount}。{@link #correct} 只表示「前端算得对不对」，
 * 不代表「是否采纳前端的金额」。
 *
 * @param clientAmount    前端传入的金额（分）；未传时为 null
 * @param serverAmount    服务端权威金额（分）
 * @param correct         前端金额是否与服务端一致（未传时视为不一致）
 * @param reason          不一致的原因，便于前端提示与排查；一致时为 null
 */
public record MemberPriceCheck(
        Long clientAmount,
        long serverAmount,
        boolean correct,
        String reason) {

    /** 前端未传金额：不做「正确」判定，但仍会以服务端金额计价。 */
    public static MemberPriceCheck clientAbsent(long serverAmount) {
        return new MemberPriceCheck(null, serverAmount, false, "客户端未提交金额，已按服务端金额计价");
    }

    /** 前端金额与服务端一致。 */
    public static MemberPriceCheck matched(long clientAmount, long serverAmount) {
        return new MemberPriceCheck(clientAmount, serverAmount, true, null);
    }

    /** 前端金额与服务端不一致：以服务端为准，并给出差异说明。 */
    public static MemberPriceCheck mismatched(long clientAmount, long serverAmount) {
        long diff = clientAmount - serverAmount;
        String reason = diff > 0
                ? "客户端金额高于服务端 " + diff + " 分，已按服务端金额计价"
                : "客户端金额低于服务端 " + (-diff) + " 分，已按服务端金额计价";
        return new MemberPriceCheck(clientAmount, serverAmount, false, reason);
    }

    /** 按前端是否传值自动判定。 */
    public static MemberPriceCheck of(Long clientAmount, long serverAmount) {
        if (clientAmount == null) {
            return clientAbsent(serverAmount);
        }
        return clientAmount == serverAmount
                ? matched(clientAmount, serverAmount)
                : mismatched(clientAmount, serverAmount);
    }
}