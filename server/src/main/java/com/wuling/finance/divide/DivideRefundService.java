package com.wuling.finance.divide;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.finance.entity.ReconcileIssue;
import com.wuling.finance.entity.SettlementRecord;
import com.wuling.finance.mapper.SettlementRecordMapper;
import com.wuling.finance.service.ReconcileService;
import com.wuling.subject.onboard.MerchantOnboardService;
import com.wuling.subject.port.SubjectQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 退款归集明细的构造。
 *
 * <p><b>为什么需要归集</b>：订单若已分账，钱已在经营者子商户账户。
 * 退款时若不动那些钱，等于<b>平台替经营者垫付</b>；若经营者已提现，
 * 平台就再也拿不回来了。
 *
 * <p><b>关键设计变更</b>：早期实现是「先调独立 {@code divide/back}，再退款」。
 * 这是错的——易宝的 {@code trade/refund} <b>自带</b> {@code divideBackDetail}
 * 参数，一次请求即可完成「归集 + 退款」。分两次调会多一个失败点，
 * 且中间状态难处理。本服务因此只负责<b>构造参数</b>，不发起任何三方请求。
 *
 * <p><b>三种情况可在同一请求里混合出现</b>（已从易宝文档确认）：
 * <ul>
 *   <li>已分账的接收方 → {@code ledgerNo + amount + divideDetailNo}（归集回来）</li>
 *   <li>未分账的部分 → {@code ledgerNo + divBackAccountType=DIVIDE_ACCOUNT}
 *       （从待分账账户直接扣，最多一条）</li>
 *   <li>已提现的接收方 → 归集会失败，转平台垫付（记对账异常追偿）</li>
 * </ul>
 *
 * <p><b>两条互斥约束</b>（易宝文档明确）：
 * 归集明细<b>不得</b>带 {@code divBackAccountType}；
 * 收单商户明细<b>不得</b>带 {@code amount}，且最多一条。
 */
@Service
public class DivideRefundService {

    private static final Logger log = LoggerFactory.getLogger(DivideRefundService.class);

    /** {@code divBackAccountType} 取值：从待分账账户扣。 */
    private static final String BACK_ACCOUNT_DIVIDE = "DIVIDE_ACCOUNT";

    private final SettlementRecordMapper settlementRecordMapper;
    private final MerchantOnboardService onboardService;
    private final ReconcileService reconcileService;
    private final SubjectQueryPort subjectQueryPort;
    private final String platformMerchantNo;
    private final boolean yeepayEnabled;

    public DivideRefundService(SettlementRecordMapper settlementRecordMapper,
                               MerchantOnboardService onboardService,
                               org.springframework.beans.factory.ObjectProvider<
                                       ReconcileService> reconcileServiceProvider,
                               SubjectQueryPort subjectQueryPort,
                               @Value("${app.pay.yeepay.platform-merchant-no:}") String platformMerchantNo,
                               @Value("${app.pay.yeepay.channel:}") String yeepayChannel) {
        this.settlementRecordMapper = settlementRecordMapper;
        this.onboardService = onboardService;
        this.reconcileService = reconcileServiceProvider.getIfAvailable();
        this.subjectQueryPort = subjectQueryPort;
        this.platformMerchantNo = platformMerchantNo;
        this.yeepayEnabled = "yeepay".equalsIgnoreCase(
                yeepayChannel == null ? "" : yeepayChannel.trim());
    }

    /**
     * 构造退款的归集明细（{@code divideBackDetail}）。
     *
     * <p>返回 null 表示<b>整单未分账</b>，此时退款不需要任何归集参数——
     * 钱还在待分账账户，直接退即可（这是最简单也最常见的路径，
     * 尤其配合售后期冻结时）。
     *
     * @param orderId        订单 id
     * @param refundAmountFen 本次退款金额（分）
     * @param refundRatio    退款比例（万分比）；为 null 时按金额比例推算
     * @return 归集明细；整单未分账时返回 null
     */
    public List<Map<String, Object>> buildDivideBackDetail(Long orderId, long refundAmountFen,
                                                           Integer refundRatio) {
        if (!yeepayEnabled || orderId == null) {
            return null;
        }
        List<SettlementRecord> records = settlementRecordMapper.selectList(
                new LambdaQueryWrapper<SettlementRecord>()
                        .eq(SettlementRecord::getOrderId, orderId));
        if (records.isEmpty()) {
            return null;
        }

        // 总份额用于按比例分摊本次退款
        long totalAmount = records.stream()
                .mapToLong(r -> nz(r.getAmount()) - nz(r.getRefundedAmount()))
                .sum();
        if (totalAmount <= 0L || refundAmountFen <= 0L) {
            return null;
        }
        int ratio = refundRatio != null
                ? Math.max(0, Math.min(10000, refundRatio))
                : (int) Math.round(refundAmountFen * 10000.0 / totalAmount);

        List<Map<String, Object>> detail = new ArrayList<>();
        boolean hasUndivided = false;
        long collectTotal = 0L;

        for (SettlementRecord record : records) {
            long remaining = nz(record.getAmount()) - nz(record.getRefundedAmount());
            if (remaining <= 0L) {
                continue;
            }
            long share = ratio >= 10000
                    ? remaining
                    : Math.round(remaining * ratio / 10000.0);
            if (share <= 0L) {
                continue;
            }

            boolean divided = SettlementRecord.DIVIDE_SUCCESS.equals(record.getDivideStatus());
            String detailNo = record.getYeepayDivideDetailNo();
            if (divided && detailNo != null && !detailNo.isBlank()) {
                // 已分账：传明细号归集（注意：不能带 divBackAccountType）
                String merchantNo = receiverMerchantNo(record.getSubjectId());
                if (merchantNo == null) {
                    log.warn("归集明细缺少接收方商编 recordId={} subjectId={}",
                            record.getId(), record.getSubjectId());
                    continue;
                }
                Map<String, Object> item = new HashMap<>();
                item.put("ledgerNo", merchantNo);
                item.put("amount", fenToYuan(share));
                item.put("divideDetailNo", detailNo);
                detail.add(item);
                collectTotal += share;
            } else if (divided) {
                // 已分账但缺明细号：无法归集，只能平台垫付
                log.error("已分账记录缺少 divideDetailNo，无法归集，需平台垫付 "
                        + "recordId={} subjectId={} orderId={} amount={}",
                        record.getId(), record.getSubjectId(), orderId, share);
                recordCollectFailure(orderId, record, share, "缺少分账明细单号");
            } else {
                // 未分账 / 待清算 / 待入驻：钱还在待分账账户
                hasUndivided = true;
            }
        }

        if (hasUndivided) {
            // 收单商户明细：只带 ledgerNo + divBackAccountType，不带金额（最多一条）
            Map<String, Object> item = new HashMap<>();
            item.put("ledgerNo", platformMerchantNoOrDefault());
            item.put("divBackAccountType", BACK_ACCOUNT_DIVIDE);
            detail.add(item);
        }

        if (detail.isEmpty()) {
            // 全部记录都已分账且都缺明细号 → 不传参数，让退款从平台账户出
            log.warn("订单无可归集明细，退款将走平台账户垫付 orderId={}", orderId);
            return null;
        }
        log.info("构造退款归集明细 orderId={} 归集明细数={} 归集金额={}分 含未分账={}",
                orderId, detail.size() - (hasUndivided ? 1 : 0), collectTotal, hasUndivided);
        return detail;
    }

    /**
     * 归集失败时记对账异常，供运营向经营者追偿。
     *
     * <p>由调用方（退款流程）在易宝返回归集失败时调用。
     *
     * @param orderNo    订单号
     * @param refundNo   退款单号
     * @param amountFen  平台垫付金额（分）
     * @param receivers  涉及的接收方商编
     * @param failReason 易宝返回的失败原因
     */
    public void recordCollectFailure(String orderNo, String refundNo, long amountFen,
                                     java.util.Set<String> receivers, String failReason) {
        if (reconcileService == null) {
            return;
        }
        try {
            reconcileService.recordOnce(
                    ReconcileIssue.TYPE_DIVIDE_BACK_FAILED,
                    orderNo, refundNo,
                    "应归集=" + amountFen + "分，接收方=" + receivers,
                    "归集失败：" + failReason + "（平台已垫付，需向经营者追偿）",
                    amountFen);
        } catch (Exception e) {
            log.error("归集失败写入对账池也失败 orderNo={} refundNo={} err={}",
                    orderNo, refundNo, e.getMessage());
        }
    }

    /** 单条记录归集失败（缺明细号等）。 */
    private void recordCollectFailure(Long orderId, SettlementRecord record,
                                      long amountFen, String reason) {
        if (reconcileService == null) {
            return;
        }
        try {
            reconcileService.recordOnce(
                    ReconcileIssue.TYPE_DIVIDE_BACK_FAILED,
                    record.getOrderNo(), null,
                    "记录=" + record.getId() + " subject=" + record.getSubjectId(),
                    reason + "（平台已垫付，需向经营者追偿）",
                    amountFen);
        } catch (Exception e) {
            log.warn("归集失败入池异常 orderId={} err={}", orderId, e.getMessage());
        }
    }

    /**
     * 解析接收方商编。
     *
     * <p>平台主体的特殊处理：它就是平台商本身，没有入驻记录，
     * 直接用配置的平台商商编。
     */
    private String receiverMerchantNo(Long subjectId) {
        if (subjectId == null) {
            return null;
        }
        if (isPlatformSubject(subjectId)) {
            return platformMerchantNoOrDefault();
        }
        String merchantNo = onboardService.yeepayMerchantNo(subjectId);
        return merchantNo == null || merchantNo.isBlank() ? null : merchantNo;
    }

    private boolean isPlatformSubject(Long subjectId) {
        try {
            Long platformId = subjectQueryPort.findFirstByType("PLATFORM");
            return platformId != null && platformId.equals(subjectId);
        } catch (Exception e) {
            return false;
        }
    }

    private String platformMerchantNoOrDefault() {
        return platformMerchantNo == null ? "" : platformMerchantNo;
    }

    /** 分 → 元（两位小数字符串，易宝要求）。 */
    private String fenToYuan(long fen) {
        return String.format("%.2f", fen / 100.0);
    }

    private long nz(Long v) {
        return v == null ? 0L : v;
    }
}
