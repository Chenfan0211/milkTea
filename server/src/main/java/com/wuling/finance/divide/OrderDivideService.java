package com.wuling.finance.divide;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.finance.entity.SettlementRecord;
import com.wuling.finance.mapper.SettlementRecordMapper;
import com.wuling.subject.onboard.MerchantOnboardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.transaction.annotation.Transactional;
/**
 * 订单分账编排。
 *
 * <p><b>时机</b>：订单核销后（{@code OrderVerifiedSplitConsumer} 本地记账完成），
 * 立即把该订单的各收款方份额分到其易宝子商户账户。
 *
 * <p><b>为什么不在提现时分账</b>：一次提现可能涉及几十笔历史订单，
 * 易宝要求同订单两次分账间隔 2-3 秒，串行下来要好几分钟，
 * 且中途失败要靠断点续跑。放在核销时做，天然分散且失败易重试。
 *
 * <p><b>未入驻处理</b>：收款方尚未入驻时，钱留在平台待分账账户
 * （易宝不会自动分账），结算记录标记 {@code PENDING_ONBOARD}，
 * 入驻完成后再由 {@link #catchUpDivides} 补分。
 *
 * <p><b>幂等</b>：{@code divide_status} 是唯一防线。
 * 只有 {@code NONE / FAIL / PENDING_ONBOARD / HOLD} 可以发起分账，
 * {@code PROCESSING / SUCCESS} 一律跳过，避免重复分账造成真实资损。
 */
@Service
public class OrderDivideService {

    private static final Logger log = LoggerFactory.getLogger(OrderDivideService.class);

    private final SettlementRecordMapper settlementRecordMapper;
    private final MerchantOnboardService onboardService;
    private final RestClient serverInternalRestClient;
    /** 快照表：仅在结算记录的 order_no 为空时用于回退查询订单号。 */
    private final com.wuling.finance.mapper.SplitSnapshotMapper splitSnapshotMapper;
    private final boolean yeepayEnabled;
    /**
     * 售后期天数：核销后 N 天内不分账，避免退款时追讨已分走的资金。
     *
     * <p>0 表示不冻结（核销即分账），可用作灰度开关。
     */
    private final long holdDays;
    /**
     * 用于查订单支付渠道（{@code payment.channel}）。
     *
     * <p>为什么直接 JDBC 而不是端口：只在 {@code DIVIDE_NONE} 的历史补分
     * 这一低频路径上使用一条 count 查询，为它单独建跨域端口不划算。
     */
    private final org.springframework.jdbc.core.JdbcTemplate paymentChannelJdbcTemplate;
    /**
     * 结算卡端口：仅用于「分账时收款方是否已绑卡」的告警判断。
     *
     * <p>用 {@code ObjectProvider} 而非直接注入：未启用易宝时该端口可能
     * 不存在实现（只有 Noop），延迟获取可避免启动期依赖问题。
     */
    private final org.springframework.beans.factory.ObjectProvider<
            com.wuling.finance.port.PayoutAccountPort> payoutAccountPortProvider;
    /**
     * 平台商商编：平台主体作为分账接收方时的 {@code ledgerNo}。
     *
     * <p>平台自己就是平台商，无需入驻，直接用此配置值。
     */
    private final String platformMerchantNo;
    /** 主体查询：判断某 subjectId 是否平台主体。 */
    private final com.wuling.subject.port.SubjectQueryPort subjectQueryPort;

    public OrderDivideService(SettlementRecordMapper settlementRecordMapper,
                              MerchantOnboardService onboardService,
                              @Qualifier("internalRestClient") RestClient internalRestClient,
                              @Value("${app.pay.yeepay.channel:}") String yeepayChannel,
                              com.wuling.finance.mapper.SplitSnapshotMapper splitSnapshotMapper,
                              @Value("${app.divide.hold-days:7}") long holdDays,
                              org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
                              org.springframework.beans.factory.ObjectProvider<
                                      com.wuling.finance.port.PayoutAccountPort> payoutAccountPortProvider,
                              @Value("${app.pay.yeepay.platform-merchant-no:}") String platformMerchantNo,
                              com.wuling.subject.port.SubjectQueryPort subjectQueryPort) {
        this.settlementRecordMapper = settlementRecordMapper;
        this.onboardService = onboardService;
        this.serverInternalRestClient = internalRestClient;
        this.splitSnapshotMapper = splitSnapshotMapper;
        this.yeepayEnabled = "yeepay".equalsIgnoreCase(
                yeepayChannel == null ? "" : yeepayChannel.trim());
        this.holdDays = Math.max(0L, holdDays);
        this.paymentChannelJdbcTemplate = jdbcTemplate;
        this.payoutAccountPortProvider = payoutAccountPortProvider;
        this.platformMerchantNo = platformMerchantNo;
        this.subjectQueryPort = subjectQueryPort;
        if (this.yeepayEnabled) {
            log.info("分账售后期冻结：holdDays={}（0 表示核销即分账）", this.holdDays);
            // 平台商商编缺失是阻塞项：没它平台份额分不出去，
            // 整笔订单会卡在「部分分账」，平台自己的钱也拿不到。
            // 不 fail-fast（会影响无关的收款功能），但必须 ERROR 级暴露，
            // 避免等到运营发现提现异常时才排查。
            if (platformMerchantNo == null || platformMerchantNo.isBlank()) {
                log.error("易宝已启用但未配置 app.pay.yeepay.platform-merchant-no："
                        + "平台份额将无法分账，订单会卡在部分分账状态。"
                        + "请向易宝开通「平台商」子商户并注入该商编。");
            }
        }
    }

    /**
     * 订单核销后分账：按订单聚合所有已入驻的收款方，一次调用分完。
     *
     * <p><b>为什么要聚合</b>：易宝同一订单有分账次数上限（{@code OPR21006}）。
     * 若每个收款方单独调一次，5 方分账要调 5 次，容易触顶。
     *
     * <p><b>售后期冻结</b>：{@code app.divide.hold-days > 0} 时，
     * 本次只把结算记录标记为 {@code HOLD} 并写入到期时间，<b>不发起分账</b>。
     * 期间用户退款的话钱还在平台待分账账户，直接退款即可，
     * 不必向经营者追讨（避免平台垫付）。到期后由 {@code DivideRetryJob} 释放。
     *
     * @param orderId  订单 id
     * @param orderNo  订单号
     */
    public void divideForOrder(Long orderId, String orderNo) {
        if (!yeepayEnabled || orderId == null) {
            return;
        }
        List<SettlementRecord> records = settlementRecordMapper.selectList(
                new LambdaQueryWrapper<SettlementRecord>()
                        .eq(SettlementRecord::getOrderId, orderId)
                        .in(SettlementRecord::getStatus,
                                List.of(LedgerServiceConstants.SETTLED, LedgerServiceConstants.SETTLEABLE)));
        if (records.isEmpty()) {
            return;
        }

        // 清算门禁：易宝要求「清算成功后才能分账」，核销可能早于清算。
        // 未清算时只标记，等清算通知到达再触发（见 YeepayNotifyInternalController）。
        // 直接调 divide/apply 会撞 OPR21016「订单状态不支持分账」。
        boolean notCleared = records.stream()
                .anyMatch(r -> SettlementRecord.CHANNEL_YEEPAY.equals(r.getFundChannel())
                        && !SettlementRecord.CLEARING_SUCCESS.equals(r.getClearingStatus()));
        if (notCleared) {
            for (SettlementRecord record : records) {
                if (record.getAmount() != null && record.getAmount() > 0L && isDivisible(record)) {
                    markPendingClearing(record);
                }
            }
            log.info("订单未清算，分账延后至清算通知 orderNo={} 记录数={}", orderNo, records.size());
            return;
        }

        // 售后期冻结：先标 HOLD，钱留在平台待分账账户
        if (holdDays > 0) {
            LocalDateTime holdUntil = LocalDateTime.now().plusDays(holdDays);
            for (SettlementRecord record : records) {
                if (record.getAmount() == null || record.getAmount() <= 0L) {
                    continue;
                }
                if (isDivisible(record)) {
                    markHold(record, holdUntil);
                }
            }
            log.info("订单分账进入售后期冻结 orderNo={} 到期={} 记录数={}",
                    orderNo, holdUntil, records.size());
            return;
        }

        List<DivideItem> ready = new ArrayList<>();
        for (SettlementRecord record : records) {
            if (record.getAmount() == null || record.getAmount() <= 0L) {
                continue;
            }
            if (isDivisible(record)) {
                // 统一走 receiverMerchantNo：平台主体用平台商商编，经营者用子商户号
                String merchantNo = receiverMerchantNo(record);
                if (merchantNo == null || merchantNo.isBlank()) {
                    // 未入驻：标记后等补分账
                    markPendingOnboard(record);
                    continue;
                }
                ready.add(new DivideItem(record, merchantNo));
            }
        }
        if (ready.isEmpty()) {
            return;
        }

        // 分账请求号：订单维度唯一，重试时复用（易宝按此幂等）
        String divideRequestId = "DV" + orderNo;
        List<Map<String, Object>> receivers = new ArrayList<>();
        for (DivideItem item : ready) {
            Map<String, Object> receiver = new HashMap<>();
            receiver.put("ledgerNo", item.merchantNo);
            receiver.put("amountFen", item.record.getAmount());
            receiver.put("description", "订单结算");
            receivers.add(receiver);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("orderId", orderNo);
        // merchantNo（收款商户=平台商）不在此处传：由 trade-service 按配置补齐。
        // 这里若传经营者子商户号会被易宝按「从子商户给自己分账」处理而失败。
        body.put("divideRequestId", divideRequestId);
        body.put("receivers", receivers);

        // 先置 PROCESSING 再调接口：即便调用后进程崩溃，也不会被误判为未分账
        for (DivideItem item : ready) {
            updateDivideStatus(item.record, SettlementRecord.DIVIDE_PROCESSING,
                    divideRequestId, 0L);
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = serverInternalRestClient.post()
                    .uri("/internal/yeepay/divide")
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            boolean accepted = result != null && Boolean.TRUE.equals(result.get("accepted"));
            String status = result == null ? null : String.valueOf(result.get("status"));
            if (accepted) {
                boolean success = "SUCCESS".equalsIgnoreCase(status);
                // 先按 ledgerNo 建映射，供回写 divideDetailNo 使用
                Map<String, String> detailNoByMerchant = parseDetailNos(result);
                for (DivideItem item : ready) {
                    updateDivideStatus(item.record,
                            success ? SettlementRecord.DIVIDE_SUCCESS : SettlementRecord.DIVIDE_PROCESSING,
                            divideRequestId,
                            success ? item.record.getAmount() : 0L);
                    if (success) {
                        writeDivideDetailNo(item.record, detailNoByMerchant.get(item.merchantNo));
                    }
                }
                log.info("订单分账已受理 orderNo={} 接收方数={} status={} 明细数={}",
                        orderNo, ready.size(), status, detailNoByMerchant.size());
            } else {
                String failReason = result == null ? "分账服务无响应" : String.valueOf(result.get("failReason"));
                for (DivideItem item : ready) {
                    updateDivideStatus(item.record, SettlementRecord.DIVIDE_FAIL, divideRequestId, 0L);
                }
                log.warn("订单分账失败 orderNo={} reason={}", orderNo, failReason);
            }
        } catch (Exception e) {
            // 网络异常：保持 PROCESSING，由补偿任务查询真实结果（不可直接置 FAIL，
            // 因为请求可能已到达易宝并成功，置 FAIL 会导致重复分账）
            log.error("订单分账调用异常 orderNo={} err={}", orderNo, e.getMessage());
        }
    }

    /**
     * 补分账：主体入驻完成后，把它所有 PENDING_ONBOARD / FAIL / HOLD 的记录补分出去。
     *
     * <p>按订单分组后逐个订单调用（不同订单可以并发，但同订单必须一次分完）。
     */
    public void catchUpDivides(Long subjectId) {
        if (!yeepayEnabled || subjectId == null) {
            return;
        }
        List<SettlementRecord> pending = settlementRecordMapper.selectList(
                new LambdaQueryWrapper<SettlementRecord>()
                        .eq(SettlementRecord::getSubjectId, subjectId)
                        .in(SettlementRecord::getDivideStatus,
                                List.of(SettlementRecord.DIVIDE_PENDING_ONBOARD,
                                        SettlementRecord.DIVIDE_FAIL,
                                        SettlementRecord.DIVIDE_HOLD))
                        .orderByAsc(SettlementRecord::getSettleDate));
        if (pending.isEmpty()) {
            return;
        }
        // 滤掉仍在售后期内的 HOLD：入驻完成不该绕过售后期冻结，
        // 否则「入驻完成 → 立刻分账」会重新引入垫付风险。
        List<SettlementRecord> releasable = pending.stream()
                .filter(r -> !isHoldActive(r))
                .toList();
        if (releasable.isEmpty()) {
            log.info("补分账跳过：{} 条记录均在售后期冻结中 subjectId={}",
                    pending.size(), subjectId);
            return;
        }
        log.info("补分账开始 subjectId={} 记录数={}（冻结中已跳过 {} 条）",
                subjectId, releasable.size(), pending.size() - releasable.size());
        // 按订单分组：同订单必须一次分完（受分账次数上限约束）
        Map<Long, List<SettlementRecord>> byOrder = new java.util.LinkedHashMap<>();
        for (SettlementRecord record : releasable) {
            if (record.getOrderId() == null) {
                continue;
            }
            byOrder.computeIfAbsent(record.getOrderId(), k -> new ArrayList<>()).add(record);
        }
        for (List<SettlementRecord> group : byOrder.values()) {
            divideRecords(group);
        }
    }

    /**
     * 批量补分账：扫描所有「可分但未分」的记录并重试。
     *
     * <p><b>为什么需要它</b>：{@code divide_status=FAIL} 与
     * {@code PENDING_ONBOARD} 此前只能靠「入驻完成」这一个事件触发补分。
     * 若入驻早已完成、只是分账调用当时失败（网络抖动 / 易宝限流），
     * 这笔钱会永久滞留，没有任何自动恢复路径。
     *
     * <p><b>只扫这两类状态</b>：
     * <ul>
     *   <li>{@code FAIL}：明确失败，可安全重试；</li>
     *   <li>{@code PENDING_ONBOARD}：当时未入驻，现在可能已入驻。</li>
     * </ul>
     * <b>刻意不碰 {@code PROCESSING}</b>：那是「已发出、结果未知」，
     * 重发会造成重复分账，必须靠 {@code divide/query} 确认而不是重发。
     *
     * <p><b>按主体分组</b>：同一主体的多条记录可能属于不同订单，
     * 而不同订单可以并发；同订单必须一次分完（受分账次数上限约束），
     * 故按订单聚合后逐单调用。
     *
     * @param limit 单轮最多处理的结算记录数，避免一次拉太多拖慢任务
     * @return 实际尝试分账的订单数
     */
    public int retryPendingDivides(int limit) {
        if (!yeepayEnabled) {
            return 0;
        }
        int cap = Math.max(1, Math.min(limit, 500));
        List<SettlementRecord> candidates = new ArrayList<>();

        // 1) FAIL / PENDING_ONBOARD：无条件重试
        candidates.addAll(settlementRecordMapper.selectList(
                new LambdaQueryWrapper<SettlementRecord>()
                        .in(SettlementRecord::getDivideStatus,
                                List.of(SettlementRecord.DIVIDE_FAIL,
                                        SettlementRecord.DIVIDE_PENDING_ONBOARD))
                        .isNotNull(SettlementRecord::getOrderId)
                        .orderByAsc(SettlementRecord::getSettleDate)
                        .last("limit " + cap)));

        // 1b) PENDING_CLEARING：清算通知可能丢失，靠这里兜底
        //     只挑已清算的（clearing_status=SUCCESS），未清算的扫了也分不了
        if (candidates.size() < cap) {
            candidates.addAll(settlementRecordMapper.selectList(
                    new LambdaQueryWrapper<SettlementRecord>()
                            .eq(SettlementRecord::getDivideStatus,
                                    SettlementRecord.DIVIDE_PENDING_CLEARING)
                            .eq(SettlementRecord::getClearingStatus,
                                    SettlementRecord.CLEARING_SUCCESS)
                            .isNotNull(SettlementRecord::getOrderId)
                            .orderByAsc(SettlementRecord::getSettleDate)
                            .last("limit " + (cap - candidates.size()))));
        }

        // 2) HOLD 已到期：售后期结束，释放并分账
        if (candidates.size() < cap) {
            candidates.addAll(settlementRecordMapper.selectList(
                    new LambdaQueryWrapper<SettlementRecord>()
                            .eq(SettlementRecord::getDivideStatus, SettlementRecord.DIVIDE_HOLD)
                            .isNotNull(SettlementRecord::getDivideHoldUntil)
                            .le(SettlementRecord::getDivideHoldUntil, LocalDateTime.now())
                            .isNotNull(SettlementRecord::getOrderId)
                            .orderByAsc(SettlementRecord::getSettleDate)
                            .last("limit " + (cap - candidates.size()))));
        }

        // 3) NONE：历史漏分记录（易宝启用前核销 / V113 回填）。
        //    必须限制 settle_date <= 昨天，避免与核销链路并发抢同一批记录；
        //    且只挑「易宝渠道」的订单，否则会把微信收的钱也去分账（重复付钱）。
        if (candidates.size() < cap) {
            candidates.addAll(settlementRecordMapper.selectList(
                    new LambdaQueryWrapper<SettlementRecord>()
                            .and(w -> w.isNull(SettlementRecord::getDivideStatus)
                                    .or().eq(SettlementRecord::getDivideStatus,
                                            SettlementRecord.DIVIDE_NONE))
                            .isNotNull(SettlementRecord::getOrderId)
                            .le(SettlementRecord::getSettleDate, LocalDate.now().minusDays(1))
                            .orderByAsc(SettlementRecord::getSettleDate)
                            .last("limit " + (cap - candidates.size()))));
        }

        if (candidates.isEmpty()) {
            return 0;
        }
        // 按订单聚合：同订单一次分完，避免撞易宝的分账次数上限
        Map<Long, List<SettlementRecord>> byOrder = new java.util.LinkedHashMap<>();
        for (SettlementRecord record : candidates) {
            byOrder.computeIfAbsent(record.getOrderId(), k -> new ArrayList<>()).add(record);
        }
        int attempted = 0;
        int skippedNone = 0;
        for (List<SettlementRecord> group : byOrder.values()) {
            // DIVIDE_NONE 的记录需先确认订单是易宝收款渠道，
            // 否则会把微信渠道的历史订单也拿去分账——那是重复付钱。
            if (isAllNoneStatus(group) && !isYeepayOrder(group.get(0).getOrderNo())) {
                skippedNone++;
                continue;
            }
            try {
                divideRecords(group);
                attempted++;
            } catch (Exception e) {
                // 单个订单失败不影响其他订单
                log.warn("补分账重试失败 orderId={} err={}",
                        group.get(0).getOrderId(), e.getMessage());
            }
        }
        log.info("分账重试任务完成 候选记录={} 涉及订单={} 尝试={} 跳过非易宝={}",
                candidates.size(), byOrder.size(), attempted, skippedNone);
        return attempted;
    }

    /**
     * 处理易宝清算结果通知。
     *
     * <p>易宝要求「清算成功后才能分账/退款」。核销时若清算未完成，
     * 分账会被标记为 {@code PENDING_CLEARING} 并停在原地；
     * 本方法在清算通知到达后把该订单的记录转为可分割，并立即触发分账。
     *
     * <p><b>幂等</b>：易宝会重试通知最多 9 次。重复调用只是把
     * {@code clearing_status} 再置一次 SUCCESS 并重跑分账，
     * 而分账本身的 {@code divide_status} 状态机保证不会重复分账。
     *
     * @param orderNo 商户订单号
     */
    @Transactional(rollbackFor = Exception.class)
    public void onClearingSuccess(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return;
        }
        List<SettlementRecord> records = settlementRecordMapper.selectList(
                new LambdaQueryWrapper<SettlementRecord>()
                        .eq(SettlementRecord::getOrderNo, orderNo));
        if (records.isEmpty()) {
            log.warn("清算通知找不到结算记录 orderNo={}", orderNo);
            return;
        }

        // 1) 置清算成功
        for (SettlementRecord record : records) {
            if (!SettlementRecord.CLEARING_SUCCESS.equals(record.getClearingStatus())) {
                SettlementRecord patch = new SettlementRecord();
                patch.setId(record.getId());
                patch.setClearingStatus(SettlementRecord.CLEARING_SUCCESS);
                settlementRecordMapper.updateById(patch);
                record.setClearingStatus(SettlementRecord.CLEARING_SUCCESS);
            }
        }
        log.info("订单清算完成，触发分账 orderNo={} 记录数={}", orderNo, records.size());

        // 2) 触发分账（未清算时被门禁拦下的那些记录现在可以走了）
        Long orderId = records.get(0).getOrderId();
        if (orderId != null) {
            divideForOrder(orderId, orderNo);
        }
    }

    /**
     * 统计「已分账成功但收款方未绑定结算卡」的结算记录数。
     *
     * <p>用于运营告警：这些钱已到经营者子商户待结算账户，但经营者提不出来。
     * 逐条 warn 会被日志淹没，汇总成数字后由 {@code DivideRetryJob} 定期上报。
     *
     * <p>实现上做了上限保护（最多扫 500 条），避免大表全表扫拖慢定时任务；
     * 返回 500 即视为「远超阈值」，足够触发告警。
     */
    public long countDividedWithoutSettleCard() {
        if (!yeepayEnabled) {
            return 0L;
        }
        List<SettlementRecord> divided = settlementRecordMapper.selectList(
                new LambdaQueryWrapper<SettlementRecord>()
                        .eq(SettlementRecord::getDivideStatus, SettlementRecord.DIVIDE_SUCCESS)
                        .gt(SettlementRecord::getDividedAmount, 0L)
                        .orderByDesc(SettlementRecord::getId)
                        .last("limit 500"));
        if (divided.isEmpty()) {
            return 0L;
        }
        // 按主体去重，避免同一主体多条记录被重复计数（告警关注「多少个经营者」）
        java.util.Set<Long> subjectIds = new java.util.HashSet<>();
        for (SettlementRecord record : divided) {
            if (record.getSubjectId() != null) {
                subjectIds.add(record.getSubjectId());
            }
        }
        long missing = 0L;
        for (Long subjectId : subjectIds) {
            if (!hasSettleCard(subjectId)) {
                missing++;
            }
        }
        return missing;
    }

    /** 该批记录是否全部处于 NONE/null 状态（需额外校验渠道）。 */
    private boolean isAllNoneStatus(List<SettlementRecord> records) {
        return records.stream().allMatch(r -> r.getDivideStatus() == null
                || SettlementRecord.DIVIDE_NONE.equals(r.getDivideStatus()));
    }

    /**
     * 收款方是否已绑定结算卡。
     *
     * <p>仅用于告警判断，<b>不阻断分账</b>——按产品决定「入驻完成即分账」，
     * 未绑卡时钱进子商户待结算账户，等经营者绑卡后即可提现。
     */
    private boolean hasSettleCard(Long subjectId) {
        try {
            com.wuling.finance.port.PayoutAccountPort port =
                    payoutAccountPortProvider.getIfAvailable();
            return port != null && port.defaultAccountId(subjectId) != null;
        } catch (Exception e) {
            // 查询失败按「有卡」处理，避免日志被误报淹没
            return true;
        }
    }

    /**
     * 判断订单是否为易宝收款渠道。
     *
     * <p><b>为什么必须判断</b>：{@code payment} 表记录每笔支付的实际通道。
     * {@code DIVIDE_NONE} 的记录里既有「易宝启用前核销的历史单」（微信收的钱，
     * 本来就不需要分账），也有「易宝启用后漏分的单」。
     * 若不区分就统一分账，会把微信渠道的钱也分给经营者——真正的重复付钱。
     *
     * <p>查不到支付记录时返回 false（保守跳过）：宁可漏分待人工，
     * 不可错分造成资损。
     */
    private boolean isYeepayOrder(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return false;
        }
        try {
            Integer count = paymentChannelJdbcTemplate.queryForObject(
                    "select count(*) from payment "
                            + "where order_no = ? and UPPER(channel) = 'YEEPAY' and deleted = 0",
                    Integer.class, orderNo);
            return count != null && count > 0;
        } catch (Exception e) {
            log.warn("查询订单支付渠道失败，保守跳过分账 orderNo={} err={}",
                    orderNo, e.getMessage());
            return false;
        }
    }

    /**
     * 对一组结算记录（同一订单）发起分账。
     *
     * <p>抽出来是为了让核销链路与补分账链路共用同一段逻辑。
     */
    private void divideRecords(List<SettlementRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        String orderNo = resolveOrderNo(records.get(0));
        if (orderNo == null) {
            // 拿不到订单号就无法构造分账请求。记 warn 跳过本单，
            // 不阻塞同批其他订单——单笔数据异常不该拖垮整个补分账。
            log.warn("补分账跳过：订单号缺失 recordId={} orderId={}",
                    records.get(0).getId(), records.get(0).getOrderId());
            return;
        }
        String divideRequestId = "DV" + orderNo;
        List<Map<String, Object>> receivers = new ArrayList<>();
        long total = 0L;
        for (SettlementRecord record : records) {
            if (record.getAmount() == null || record.getAmount() <= 0L) {
                continue;
            }
            // 按每条记录自己的 subjectId 解析接收方，
            // 不能用「第一条的商户号」套全批——同订单可能涉及多个主体。
            String receiverNo = receiverMerchantNo(record);
            if (receiverNo == null) {
                log.warn("补分账跳过单条：接收方未入驻 recordId={} subjectId={}",
                        record.getId(), record.getSubjectId());
                continue;
            }
            // 入驻已完成但未绑结算卡：仍然分账（钱进子商户待结算账户），
            // 但记 warn 便于运营跟进——否则经营者「看得到钱却提不出」且查不到原因。
            // 平台主体例外：它不需要结算卡，份额直接进平台资金账户。
            if (!isPlatformSubject(record.getSubjectId()) && !hasSettleCard(record.getSubjectId())) {
                log.warn("分账收款方尚未绑定结算卡，资金将滞留在子商户待结算账户 "
                                + "subjectId={} orderNo={} amount={}",
                        record.getSubjectId(), orderNo, record.getAmount());
            }
            Map<String, Object> receiver = new HashMap<>();
            receiver.put("ledgerNo", receiverNo);
            receiver.put("amountFen", record.getAmount());
            receiver.put("description", "订单结算补分");
            receivers.add(receiver);
            total += record.getAmount();
        }
        if (receivers.isEmpty()) {
            return;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("orderId", orderNo);
        // merchantNo（收款商户=平台商）由 trade-service 按配置补齐，此处不传。
        body.put("divideRequestId", divideRequestId);
        body.put("receivers", receivers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = serverInternalRestClient.post()
                    .uri("/internal/yeepay/divide")
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            boolean accepted = result != null && Boolean.TRUE.equals(result.get("accepted"));
            String status = result == null ? null : String.valueOf(result.get("status"));
            boolean success = accepted && "SUCCESS".equalsIgnoreCase(status);
            for (SettlementRecord record : records) {
                updateDivideStatus(record,
                        success ? SettlementRecord.DIVIDE_SUCCESS
                                : accepted ? SettlementRecord.DIVIDE_PROCESSING
                                : SettlementRecord.DIVIDE_FAIL,
                        divideRequestId,
                        success ? record.getAmount() : 0L);
            }
            if (success) {
                log.info("补分账成功 orderNo={} 金额={}分", orderNo, total);
            }
        } catch (Exception e) {
            log.error("补分账调用异常 orderNo={} err={}", orderNo, e.getMessage());
        }
    }

    /** 只有「未分过且不在售后期冻结中」的记录可以发起分账。 */
    private boolean isDivisible(SettlementRecord record) {
        String status = record.getDivideStatus();
        // HOLD 需单独判断：仍在冻结期的不能分，到期的可以
        if (SettlementRecord.DIVIDE_HOLD.equals(status)) {
            return !isHoldActive(record);
        }
        return status == null
                || SettlementRecord.DIVIDE_NONE.equals(status)
                || SettlementRecord.DIVIDE_FAIL.equals(status)
                || SettlementRecord.DIVIDE_PENDING_ONBOARD.equals(status)
                || SettlementRecord.DIVIDE_PENDING_CLEARING.equals(status);
    }

    /**
     * 标记待清算。
     *
     * <p>这不是终态：清算通知到达后会转回可分割状态，由
     * {@code divideForOrder} 或 {@code DivideRetryJob} 重新处理。
     */
    private void markPendingClearing(SettlementRecord record) {
        if (SettlementRecord.DIVIDE_PENDING_CLEARING.equals(record.getDivideStatus())) {
            return;
        }
        SettlementRecord patch = new SettlementRecord();
        patch.setId(record.getId());
        patch.setDivideStatus(SettlementRecord.DIVIDE_PENDING_CLEARING);
        settlementRecordMapper.updateById(patch);
        record.setDivideStatus(SettlementRecord.DIVIDE_PENDING_CLEARING);
    }

    private void markPendingOnboard(SettlementRecord record) {
        if (SettlementRecord.DIVIDE_PENDING_ONBOARD.equals(record.getDivideStatus())) {
            return;
        }
        SettlementRecord patch = new SettlementRecord();
        patch.setId(record.getId());
        patch.setDivideStatus(SettlementRecord.DIVIDE_PENDING_ONBOARD);
        settlementRecordMapper.updateById(patch);
    }

    /**
     * 标记售后期冻结。
     *
     * <p>已处于 HOLD 且到期时间未变时跳过，避免重复更新。
     */
    private void markHold(SettlementRecord record, LocalDateTime holdUntil) {
        if (SettlementRecord.DIVIDE_HOLD.equals(record.getDivideStatus())
                && record.getDivideHoldUntil() != null
                && record.getDivideHoldUntil().equals(holdUntil)) {
            return;
        }
        SettlementRecord patch = new SettlementRecord();
        patch.setId(record.getId());
        patch.setDivideStatus(SettlementRecord.DIVIDE_HOLD);
        patch.setDivideHoldUntil(holdUntil);
        settlementRecordMapper.updateById(patch);
    }

    /** 该记录是否仍处于售后期冻结中。 */
    private boolean isHoldActive(SettlementRecord record) {
        if (!SettlementRecord.DIVIDE_HOLD.equals(record.getDivideStatus())) {
            return false;
        }
        return record.getDivideHoldUntil() != null
                && record.getDivideHoldUntil().isAfter(LocalDateTime.now());
    }

    /**
     * 从分账响应里提取「ledgerNo → divideDetailNo」映射。
     *
     * <p>这是退款归集的技术前提：易宝归集要求传明细级单号，
     * 只存批次级 {@code uniqueDivideNo} 无法定位到某个接收方。
     *
     * <p>响应缺失该字段时返回空 map，调用方会记 warn，
     * 此时该笔订单的退款只能走「未分账」路径或人工处理。
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> parseDetailNos(Map<String, Object> result) {
        Map<String, String> map = new HashMap<>();
        if (result == null) {
            return map;
        }
        Object raw = result.get("details");
        if (!(raw instanceof List<?> list)) {
            return map;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> detail)) {
                continue;
            }
            Object ledgerNo = detail.get("ledgerNo");
            Object detailNo = detail.get("divideDetailNo");
            if (ledgerNo != null && detailNo != null) {
                String no = String.valueOf(detailNo);
                if (!no.isBlank()) {
                    map.put(String.valueOf(ledgerNo), no);
                }
            }
        }
        return map;
    }

    /**
     * 回写分账明细单号。
     *
     * <p>没有这个号，该条结算记录在退款时无法参与归集
     * （易宝会拒绝或只能从平台账户垫付）。
     */
    private void writeDivideDetailNo(SettlementRecord record, String detailNo) {
        if (detailNo == null || detailNo.isBlank()) {
            log.warn("分账响应未返回 divideDetailNo，该笔退款将无法归集 "
                    + "recordId={} subjectId={}", record.getId(), record.getSubjectId());
            return;
        }
        SettlementRecord patch = new SettlementRecord();
        patch.setId(record.getId());
        patch.setYeepayDivideDetailNo(detailNo);
        settlementRecordMapper.updateById(patch);
        record.setYeepayDivideDetailNo(detailNo);
    }

    private void updateDivideStatus(SettlementRecord record, String status,
                                    String requestId, long dividedAmount) {
        SettlementRecord patch = new SettlementRecord();
        patch.setId(record.getId());
        patch.setDivideStatus(status);
        patch.setDivideRequestId(requestId);
        if (dividedAmount > 0L) {
            patch.setDividedAmount(dividedAmount);
            patch.setDivideTime(LocalDateTime.now());
        }
        settlementRecordMapper.updateById(patch);
    }

    private String item0MerchantNo(List<DivideItem> items) {
        return items.get(0).merchantNo;
    }

    /**
     * 解析主体对应的易宝分账接收方编号（{@code ledgerNo}）。
     *
     * <p><b>平台主体的特殊处理</b>：平台自己就是「平台商」本身，
     * 不需要（也不可能有）入驻记录。它的 {@code ledgerNo} 直接用
     * {@code app.pay.yeepay.platform-merchant-no} 配置值。
     *
     * <p>这样做的业务意义：平台份额能与已入驻经营者的份额在同一批
     * {@code divide/apply} 里一起分走，<b>不必等所有经营者都入驻</b>。
     * 否则订单会长期卡在「部分分账」，平台自己的现金流被冻结。
     *
     * <p>与 {@link #item0MerchantNo} 的区别：这里按每条记录自己的
     * {@code subjectId} 查，避免多主体同订单时串号。
     */
    private String receiverMerchantNo(SettlementRecord record) {
        if (record == null || record.getSubjectId() == null) {
            return null;
        }
        // 平台主体：直接用平台商商编（已确认易宝允许 merchantNo == ledgerNo）
        if (isPlatformSubject(record.getSubjectId())) {
            String platformNo = platformMerchantNo;
            if (platformNo == null || platformNo.isBlank()) {
                log.warn("平台商商编未配置，平台份额无法分账 subjectId={}", record.getSubjectId());
                return null;
            }
            return platformNo;
        }
        String merchantNo = onboardService.yeepayMerchantNo(record.getSubjectId());
        return merchantNo == null || merchantNo.isBlank() ? null : merchantNo;
    }

    /** 该主体是否为平台主体。 */
    private boolean isPlatformSubject(Long subjectId) {
        if (subjectId == null) {
            return false;
        }
        try {
            Long platformId = subjectQueryPort.findFirstByType("PLATFORM");
            return platformId != null && platformId.equals(subjectId);
        } catch (Exception e) {
            log.warn("查询平台主体失败 subjectId={} err={}", subjectId, e.getMessage());
            return false;
        }
    }

    /**
     * 解析订单号：优先用结算记录上的冗余列，为空时回退查一次快照。
     *
     * <p><b>为什么需要回退</b>：{@code order_no} 列是 V113 才加的，
     * 早期数据靠迁移按 order_id 回填；若某条记录既没回填成功、
     * 又是在补写逻辑上线前产生的，就会出现 orderNo 为空。
     * 此时代码若不回退，这笔钱将永远无法分账。
     *
     * <p>回退是低频路径（正常情况 orderNo 一定有值），
     * 因此只在必要时查一次，不引入额外开销。
     */
    private String resolveOrderNo(SettlementRecord record) {
        if (record == null) {
            return null;
        }
        if (record.getOrderNo() != null && !record.getOrderNo().isBlank()) {
            return record.getOrderNo();
        }
        if (record.getSnapshotId() == null || splitSnapshotMapper == null) {
            return null;
        }
        try {
            com.wuling.finance.entity.SplitSnapshot snapshot =
                    splitSnapshotMapper.selectById(record.getSnapshotId());
            if (snapshot != null && snapshot.getOrderNo() != null) {
                // 顺手补回冗余列，避免每次补分账都再查一次快照
                SettlementRecord patch = new SettlementRecord();
                patch.setId(record.getId());
                patch.setOrderNo(snapshot.getOrderNo());
                settlementRecordMapper.updateById(patch);
                record.setOrderNo(snapshot.getOrderNo());
                return snapshot.getOrderNo();
            }
        } catch (Exception e) {
            log.warn("回退查询订单号失败 recordId={} snapshotId={} err={}",
                    record.getId(), record.getSnapshotId(), e.getMessage());
        }
        return null;
    }

    private record DivideItem(SettlementRecord record, String merchantNo) {
    }

    /**
     * 结算状态常量。
     *
     * <p>放在这里而不是引用 {@code LedgerService}，是为了避免 finance.divide
     * 对 finance.service 的反向依赖成环。
     */
    private static final class LedgerServiceConstants {
        static final String SETTLED = "SETTLED";
        static final String SETTLEABLE = "SETTLEABLE";
    }
}
