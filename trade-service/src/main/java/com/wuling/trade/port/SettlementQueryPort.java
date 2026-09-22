package com.wuling.trade.port;

/**
 * 结算状态查询端口（第 7 期）。
 *
 * <p>用途：退款受理前的**同步**校验 —— 订单若已进入可结算/已结算状态则拒绝退款，
 * 防止「钱已进可用余额却仍被退款」的资金穿透。
 *
 * <p>为什么单独抽一个端口而不是合并进 {@link ProductQueryPort}：
 * 两者语义不同（商品查询 vs 资金状态），且资金状态的失败语义更严格
 * （查询失败必须保守处理，不能当"未结算"放过）。
 */
public interface SettlementQueryPort {

    /** 结算状态视图 */
    class SettlementStatus {
        /** 是否已进入可结算/已结算（true 则不可退款） */
        private boolean settled;
        /** 是否存在分账记录（决定退款时是否需要冲正） */
        private boolean hasSettlement;

        public boolean isSettled() {
            return settled;
        }

        public void setSettled(boolean settled) {
            this.settled = settled;
        }

        public boolean isHasSettlement() {
            return hasSettlement;
        }

        public void setHasSettlement(boolean hasSettlement) {
            this.hasSettlement = hasSettlement;
        }
    }

    /**
     * 查询订单结算状态。
     *
     * @param orderId 订单 ID
     * @return 结算状态；<b>查询失败时抛异常</b>（不得返回"未结算"）
     */
    SettlementStatus query(Long orderId);
}
