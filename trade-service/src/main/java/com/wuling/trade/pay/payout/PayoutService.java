package com.wuling.trade.pay.payout;

import com.wuling.trade.port.UserQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 出款业务编排（提现 → 微信商家转账）。
 *
 * <p>职责：查收款人 openid（服务端按 userId，绝不接受前端传入）→ 调出款通道 →
 * 返回受理结果。状态收敛（PAID / FAILED / 解冻）由调用方 server 负责，
 * 本服务只负责「发起转账」这一动作。
 *
 * <p><b>fail-closed</b>：openid 缺失、金额非法、受理失败均返回明确失败信号，
 * 由 server 侧解冻，绝不静默成功。
 */
@Service
public class PayoutService {

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);

    private final PayoutGatewayResolver gatewayResolver;
    private final UserQueryPort userQueryPort;

    public PayoutService(PayoutGatewayResolver gatewayResolver,
                         UserQueryPort userQueryPort) {
        this.gatewayResolver = gatewayResolver;
        this.userQueryPort = userQueryPort;
    }

    /**
     * 发起提现出款（受理）。
     *
     * @param withdrawNo 提现单号（作为微信 out_detail_no，全局唯一，保证幂等）
     * @param amountFen  金额（分）
     * @param userId     申请人（用于查 openid）
     * @param remark     转账备注
     * @return 受理结果（accepted / batchNo / failReason）
     */
    /** 当前通道是否即时到账（mock=true / wxpay=false）。 */
    public boolean isImmediateChannel() {
        return gatewayResolver.active().isImmediate();
    }

    public PayoutGateway.PayoutResult apply(String withdrawNo, long amountFen, Long userId, String remark) {
        String openid = userQueryPort.findOpenid(userId);
        if (openid == null) {
            log.warn("提现出款：openid 缺失 withdrawNo={} userId={}", withdrawNo, userId);
            return new PayoutGateway.PayoutResult(false, null, "收款账户信息缺失，请重新登录后再试");
        }
        PayoutGateway.PayoutResult result = gatewayResolver.active()
                .transfer(withdrawNo, amountFen, openid, remark);
        log.info("提现出款受理 withdrawNo={} channel={} accepted={}",
                withdrawNo, gatewayResolver.activeChannel(), result.accepted());
        return result;
    }
}
