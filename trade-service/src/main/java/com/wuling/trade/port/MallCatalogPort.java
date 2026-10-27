package com.wuling.trade.port;

import java.util.List;

public interface MallCatalogPort {
    List<SkuView> findSkus(List<Long> skuIds);
    StockResult reserve(String orderNo, List<StockLine> lines);
    StockResult confirm(String orderNo, List<StockLine> lines);
    StockResult release(String orderNo, List<StockLine> lines);
    StockResult restore(String orderNo, List<StockLine> lines);

    /**
     * 售后回补：orderNo 仍用于校验原订单扣减，bizNo 作为本次退款幂等键。
     * 旧实现默认 bizNo=orderNo。
     */
    default StockResult restore(String orderNo, String bizNo, List<StockLine> lines) {
        return restore(orderNo, lines);
    }

    record StockLine(Long skuId, int quantity) {}

    record SkuView(Long skuId, Long productId, String productCode, String name, String specValue,
                   String image, long salePrice, long originalPrice, long costPrice,
                   long platformCommission, long distributionCommission, Long supplierSubjectId, Long storeSubjectId,
                   Long channelSubjectId, Long splitRuleId, int availableStock, boolean onSale) {}

    record StockResult(boolean success, String message) {
        public static StockResult ok() { return new StockResult(true, null); }
        public static StockResult fail(String message) { return new StockResult(false, message); }
    }
}
