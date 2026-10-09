package com.wuling.trade.controller;

import com.wuling.common.api.Result;
import com.wuling.trade.dto.OrderDTO;
import com.wuling.trade.dto.RefundRequest;
import com.wuling.trade.service.RefundService;
import com.wuling.trade.service.RefundStatusReconcileJob;
import org.springframework.web.bind.annotation.*;

/** 后台：整单退款 */
@RestController
@RequestMapping("/api/v1/admin/trade")
public class RefundController {

    private final RefundService refundService;
    private final RefundStatusReconcileJob refundStatusReconcileJob;

    public RefundController(RefundService refundService,
                            RefundStatusReconcileJob refundStatusReconcileJob) {
        this.refundService = refundService;
        this.refundStatusReconcileJob = refundStatusReconcileJob;
    }

    @PostMapping("/refund")
    public Result<OrderDTO> refund(@RequestParam String orderNo, @RequestBody(required = false) RefundRequest request) {
        String reason = request == null ? null : request.getReason();
        return Result.ok(refundService.refund(orderNo, reason));
    }

    /** 重新退款：仅退款失败(FAILED)的单据可重试 */
    @PostMapping("/refunds/{id}/retry")
    public Result<OrderDTO> retry(@PathVariable Long id) {
        return Result.ok(refundService.retry(id));
    }

    /**
     * 后台运维：按退款单号主动查询三方退款状态并推进本地状态。
     *
     * <p>用于退款结果通知回调丢失、退款单卡在 REFUNDING 时人工触发兜底查询。
     * mock 通道下 queryRefund 返回 null，不会推进状态，返回当前状态即可。
     */
    @PostMapping("/refunds/{refundNo}/sync-status")
    public Result<RefundService.RefundSyncResult> syncStatus(@PathVariable String refundNo) {
        return Result.ok(refundService.syncRefundStatusByRefundNo(refundNo));
    }

    /**
     * 后台运维：手动触发一次「退款状态主动查询兜底」整批扫描。
     *
     * <p>与定时任务 {@link RefundStatusReconcileJob} 复用同一套扫描逻辑，
     * 返回本轮推进的退款单数量。
     */
    @PostMapping("/refunds/sync-sweep")
    public Result<Integer> syncSweep() {
        return Result.ok(refundStatusReconcileJob.sweep());
    }
}