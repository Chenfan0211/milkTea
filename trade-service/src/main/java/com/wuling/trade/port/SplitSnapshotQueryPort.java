package com.wuling.trade.port;

/**
 * 分账快照查询端口。
 *
 * <p>用途：运营后台的订单列表/详情要在「分账明细」里展示五方份额，
 * 而 split_snapshot 归属 finance 域（由 server 承载）。trade 若直接读该表
 * 会破坏服务边界，故抽成本端口，由 server 的只读内部接口提供数据。
 *
 * <p><b>失败语义</b>：与 {@link SettlementQueryPort} 不同 —— 分账明细是
 * 「展示增强」，查询失败时返回 null 即可（前端会提示暂无快照），
 * 不应让整个订单列表接口失败。
 */
public interface SplitSnapshotQueryPort {

    /**
     * 分账快照视图（金额单位：分）。
     *
     * <p>costTotal / platformCommission 已是「单价 × 件数」的合计值，
     * 调用方不要再次乘以件数。
     */
    class SplitSnapshotView {
        /** 快照号 */
        private String snapshotNo;
        /** 商品件数 */
        private Integer itemCount;
        /** 供应商成本合计 = Σ(成本单价 × 件数) */
        private Long costTotal;
        /** 门店所得 */
        private Long storeShare;
        /** 资源方所得 */
        private Long channelShare;
        /** 投资人所得 */
        private Long investorShare;
        /** 平台提成合计 = Σ(提成单价 × 件数) */
        private Long platformCommission;
        /** 平台剩余 */
        private Long platformShare;
        /** 投资人计费基础额（<= 0 时投资人为 0） */
        private Long base;

        public String getSnapshotNo() {
            return snapshotNo;
        }

        public void setSnapshotNo(String snapshotNo) {
            this.snapshotNo = snapshotNo;
        }

        public Integer getItemCount() {
            return itemCount;
        }

        public void setItemCount(Integer itemCount) {
            this.itemCount = itemCount;
        }

        public Long getCostTotal() {
            return costTotal;
        }

        public void setCostTotal(Long costTotal) {
            this.costTotal = costTotal;
        }

        public Long getStoreShare() {
            return storeShare;
        }

        public void setStoreShare(Long storeShare) {
            this.storeShare = storeShare;
        }

        public Long getChannelShare() {
            return channelShare;
        }

        public void setChannelShare(Long channelShare) {
            this.channelShare = channelShare;
        }

        public Long getInvestorShare() {
            return investorShare;
        }

        public void setInvestorShare(Long investorShare) {
            this.investorShare = investorShare;
        }

        public Long getPlatformCommission() {
            return platformCommission;
        }

        public void setPlatformCommission(Long platformCommission) {
            this.platformCommission = platformCommission;
        }

        public Long getPlatformShare() {
            return platformShare;
        }

        public void setPlatformShare(Long platformShare) {
            this.platformShare = platformShare;
        }

        public Long getBase() {
            return base;
        }

        public void setBase(Long base) {
            this.base = base;
        }
    }

    /**
     * 按订单号查询分账快照。
     *
     * @param orderNo 订单号
     * @return 快照视图；无快照或查询失败返回 null
     */
    SplitSnapshotView findByOrderNo(String orderNo);
}