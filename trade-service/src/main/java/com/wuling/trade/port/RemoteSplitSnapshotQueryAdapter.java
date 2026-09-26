package com.wuling.trade.port;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * {@link SplitSnapshotQueryPort} 的远程实现。
 *
 * <p>调用 server 的 {@code /internal/split-snapshot} 只读接口。
 *
 * <p><b>失败语义</b>：任何异常都返回 null（只记日志）。
 * 分账明细属于展示增强，订单列表不应因为 finance 侧抖动而整体不可用；
 * 无快照时前端会提示「该订单尚未核销」。
 */
@Component
public class RemoteSplitSnapshotQueryAdapter implements SplitSnapshotQueryPort {

    private static final Logger log = LoggerFactory.getLogger(RemoteSplitSnapshotQueryAdapter.class);

    private final RestClient restClient;

    public RemoteSplitSnapshotQueryAdapter(
            @Qualifier("serverInternalRestClient") RestClient serverInternalRestClient) {
        this.restClient = serverInternalRestClient;
    }

    @Override
    @SuppressWarnings("unchecked")
    public SplitSnapshotView findByOrderNo(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> body = restClient.get()
                    .uri("/internal/split-snapshot?orderNo={no}", orderNo)
                    .retrieve()
                    .body(Map.class);
            if (body == null || body.get("snapshotNo") == null) {
                return null;
            }
            SplitSnapshotView view = new SplitSnapshotView();
            view.setSnapshotNo(String.valueOf(body.get("snapshotNo")));
            view.setItemCount(asInt(body.get("itemCount")));
            view.setCostTotal(asLong(body.get("costTotal")));
            view.setStoreShare(asLong(body.get("storeShare")));
            view.setChannelShare(asLong(body.get("channelShare")));
            view.setInvestorShare(asLong(body.get("investorShare")));
            view.setPlatformCommission(asLong(body.get("platformCommission")));
            view.setPlatformShare(asLong(body.get("platformShare")));
            view.setBase(asLong(body.get("base")));
            return view;
        } catch (Exception e) {
            log.warn("查询分账快照失败 orderNo={} err={}", orderNo, e.getMessage());
            return null;
        }
    }

    private Long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    private Integer asInt(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }
}