package com.wuling.common.mq.event;

import java.io.Serializable;
import java.util.List;

/**
 * 核销分账事件（第 6 期）。
 *
 * <p>由 trade 服务在订单核销后发布，finance 服务消费并执行五方分账。
 *
 * <p>为什么要传这么多字段而不是只传 orderId：
 * 分账需要「订单支付金额、门店/渠道主体、商品明细分摊」等信息。
 * 若只传 orderId，finance 就得反查 trade 的订单表 —— 又是一层跨服务依赖。
 * 核销时这些数据已在 trade 内存中，直接随事件携带最省事，
 * 也让 finance 的分账逻辑不依赖上游表结构（更彻底的服务边界）。
 *
 * <p>注意：字段需保持向后兼容（消费端忽略未知字段），
 * 后续新增字段不要改动已有字段语义。
 */
public class OrderVerifiedEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订单 ID */
    private Long orderId;
    /** 订单号 */
    private String orderNo;
    /** 实付金额（分） */
    private Long paidAmount;
    /** 明细条数 */
    private Integer itemCount;
    /** 门店主体 ID */
    private Long storeSubjectId;
    /** 渠道主体 ID（可空） */
    private Long channelSubjectId;
    /** 首个商品 ID（用于解析分账规则；可空） */
    private Long firstProductId;
    /** 平台佣金（分） */
    private Long platformCommission;
    /** 明细：供应商主体 + 金额（用于供应商份额按明细分摊） */
    private List<Line> lines;

    /** 明细行 */
    public static class Line implements Serializable {
        private static final long serialVersionUID = 1L;

        /** 供应商主体 ID（可空，空则该行的供应商份额无法归属） */
        private Long supplierSubjectId;
        /** 行金额（分） */
        private Long amount;

        public Line() {
        }

        public Line(Long supplierSubjectId, Long amount) {
            this.supplierSubjectId = supplierSubjectId;
            this.amount = amount;
        }

        public Long getSupplierSubjectId() {
            return supplierSubjectId;
        }

        public void setSupplierSubjectId(Long supplierSubjectId) {
            this.supplierSubjectId = supplierSubjectId;
        }

        public Long getAmount() {
            return amount;
        }

        public void setAmount(Long amount) {
            this.amount = amount;
        }
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Long getPaidAmount() {
        return paidAmount;
    }

    public void setPaidAmount(Long paidAmount) {
        this.paidAmount = paidAmount;
    }

    public Integer getItemCount() {
        return itemCount;
    }

    public void setItemCount(Integer itemCount) {
        this.itemCount = itemCount;
    }

    public Long getStoreSubjectId() {
        return storeSubjectId;
    }

    public void setStoreSubjectId(Long storeSubjectId) {
        this.storeSubjectId = storeSubjectId;
    }

    public Long getChannelSubjectId() {
        return channelSubjectId;
    }

    public void setChannelSubjectId(Long channelSubjectId) {
        this.channelSubjectId = channelSubjectId;
    }

    public Long getFirstProductId() {
        return firstProductId;
    }

    public void setFirstProductId(Long firstProductId) {
        this.firstProductId = firstProductId;
    }

    public Long getPlatformCommission() {
        return platformCommission;
    }

    public void setPlatformCommission(Long platformCommission) {
        this.platformCommission = platformCommission;
    }

    public List<Line> getLines() {
        return lines;
    }

    public void setLines(List<Line> lines) {
        this.lines = lines;
    }
}
