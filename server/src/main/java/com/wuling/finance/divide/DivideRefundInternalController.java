package com.wuling.finance.divide;

import com.wuling.finance.entity.SettlementRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 退款归集明细内部接口（供 trade-service 在发起易宝退款时调用）。
 *
 * <p><b>为什么由 server 提供</b>：归集明细依赖 {@code settlement_record}
 * 的分账进度与 {@code yeepay_divide_detail_no}，这些数据属 finance 域。
 * trade-service 发起退款时通过本接口索取，避免反向依赖 finance 的表结构。
 *
 * <p><b>安全</b>：{@code /internal/**} 不经网关路由，各服务仅监听 127.0.0.1。
 */
@RestController
@RequestMapping("/internal/yeepay")
public class DivideRefundInternalController {

    private static final Logger log = LoggerFactory.getLogger(DivideRefundInternalController.class);

    private final DivideRefundService divideRefundService;

    public DivideRefundInternalController(DivideRefundService divideRefundService) {
        this.divideRefundService = divideRefundService;
    }

    /**
     * 构造退款的 {@code divideBackDetail}。
     *
     * @param body { orderId, refundAmountFen, refundRatio }
     * @return { detail: [...] }；整单未分账时 detail 为空数组
     */
    @PostMapping("/refund/divide-back-detail")
    public Map<String, Object> buildDetail(@RequestBody Map<String, Object> body) {
        Long orderId = toLong(body.get("orderId"));
        long refundAmountFen = toLong(body.get("refundAmountFen")) == null
                ? 0L : toLong(body.get("refundAmountFen"));
        Long ratioRaw = toLong(body.get("refundRatio"));
        Integer refundRatio = ratioRaw == null ? null : ratioRaw.intValue();

        List<Map<String, Object>> detail;
        try {
            detail = divideRefundService.buildDivideBackDetail(orderId, refundAmountFen, refundRatio);
        } catch (Exception e) {
            // 归集明细构造失败不能让退款卡住：返回空，退款走平台账户
            log.error("构造归集明细失败，退款将走平台账户 orderId={} err={}",
                    orderId, e.getMessage());
            detail = null;
        }
        return Map.of("detail", detail == null ? List.of() : detail);
    }

    /**
     * 上报归集失败（供 trade-service 在易宝返回归集失败时调用）。
     *
     * <p>归集失败意味着平台已垫付，必须入对账池否则无人追偿。
     */
    @PostMapping("/refund/collect-failure")
    @SuppressWarnings("unchecked")
    public Map<String, Object> collectFailure(@RequestBody Map<String, Object> body) {
        String orderNo = str(body.get("orderNo"));
        String refundNo = str(body.get("refundNo"));
        long amountFen = toLong(body.get("amountFen")) == null ? 0L : toLong(body.get("amountFen"));
        String failReason = str(body.get("failReason"));
        List<String> receivers = body.get("receivers") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
        divideRefundService.recordCollectFailure(orderNo, refundNo, amountFen,
                new java.util.HashSet<>(receivers), failReason);
        return Map.of("ok", true);
    }

    private Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
