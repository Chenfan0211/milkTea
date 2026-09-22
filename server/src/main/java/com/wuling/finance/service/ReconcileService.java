package com.wuling.finance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.common.alert.AlertChannel;
import com.wuling.finance.entity.ReconcileIssue;
import com.wuling.finance.entity.SettlementRecord;
import com.wuling.finance.entity.SplitSnapshot;
import com.wuling.finance.mapper.ReconcileIssueMapper;
import com.wuling.finance.mapper.SettlementRecordMapper;
import com.wuling.finance.mapper.SplitSnapshotMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 资金对账服务（第 8 期）。
 *
 * <p>背景：第 6 期把分账/冲正改为 MQ 事件后，资金链路变成<b>最终一致</b>。
 * 若消息丢失或进入死信，会出现「业务已完成但账目未动」的静默不一致 ——
 * 没有任何机制会发现。本服务就是对这一风险的兜底。
 *
 * <p>检查项：
 * <ol>
 *   <li><b>核销后未分账</b>：订单状态为 VERIFIED/COMPLETED，但 split_snapshot 无记录；</li>
 *   <li><b>退款后未冲正</b>：订单已退款，但仍有 PENDING 的 settlement_record；</li>
 *   <li><b>分账金额不一致</b>：快照五方之和 ≠ 订单实付金额。</li>
 * </ol>
 *
 * <p><b>核心原则：只发现、不自动改账</b>。
 * 资金修复涉及多方账目（平台/门店/渠道/投资人/供应商），
 * 自动修改的风险高于人工介入。因此本服务只写入 reconcile_issue 并告警，
 * 由人工确认后再决定是否补偿。
 *
 * <p>幂等：同一 (issueType, orderNo) 在 OPEN 状态下不重复记录，
 * 避免每小时对账产生大量重复告警。
 */
@Service
public class ReconcileService {

    private static final Logger log = LoggerFactory.getLogger(ReconcileService.class);

    /** 视为"已消费"的订单状态 */
    private static final List<String> CONSUMED_STATUS = List.of("VERIFIED", "COMPLETED");

    private final JdbcTemplate jdbcTemplate;
    private final SplitSnapshotMapper splitSnapshotMapper;
    private final SettlementRecordMapper settlementRecordMapper;
    private final ReconcileIssueMapper reconcileIssueMapper;
    /** 第 9 期：告警通道（当前为日志通道，可插拔） */
    private final AlertChannel alertChannel;

    public ReconcileService(JdbcTemplate jdbcTemplate,
                            SplitSnapshotMapper splitSnapshotMapper,
                            SettlementRecordMapper settlementRecordMapper,
                            ReconcileIssueMapper reconcileIssueMapper,
                            AlertChannel alertChannel) {
        this.jdbcTemplate = jdbcTemplate;
        this.splitSnapshotMapper = splitSnapshotMapper;
        this.settlementRecordMapper = settlementRecordMapper;
        this.reconcileIssueMapper = reconcileIssueMapper;
        this.alertChannel = alertChannel;
    }

    /**
     * 执行一轮对账。
     *
     * @return 本轮发现的问题数（新增，不含已存在的 OPEN 项）
     */
    public int reconcile() {
        int found = 0;
        found += checkMissingSplit();
        found += checkMissingReverse();
        found += checkSplitAmountMismatch();
        if (found > 0) {
            log.error("对账发现 {} 处资金不一致，请立即核查 reconcile_issue 表", found);
            // 第 9 期：通过告警通道通知（CRITICAL 级别，需立即人工介入）
            alertChannel.send(AlertChannel.Level.CRITICAL,
                    "对账发现资金不一致",
                    "本轮新增 " + found + " 处异常，请核查 reconcile_issue 表并处理",
                    null);
        }
        return found;
    }

    /**
     * 检查项 1：已核销但无分账快照。
     *
     * <p>这是 MQ 分账事件丢失的典型症状：门店已放行、订单已消费，
     * 但没有任何一方收到钱。
     */
    private int checkMissingSplit() {
        // 已核销/已完成的订单，且不存在分账快照
        String sql = "select o.order_no, o.paid_amount from orders o "
                + "left join split_snapshot s on s.order_id = o.id and s.deleted = 0 "
                + "where o.deleted = 0 and o.status in (" + placeholders(CONSUMED_STATUS.size()) + ") "
                + "and s.id is null "
                + "limit 500";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, CONSUMED_STATUS.toArray());

        int count = 0;
        for (Map<String, Object> row : rows) {
            String orderNo = (String) row.get("order_no");
            long paid = toLong(row.get("paid_amount"));
            if (recordOnce(ReconcileIssue.TYPE_MISSING_SPLIT, orderNo,
                    "无分账快照", "应有五方分账快照", paid)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 检查项 2：已退款但未冲正。
     *
     * <p>退款冲正消息丢失的症状：订单已退款给用户，
     * 但各方台账仍挂着 PENDING（钱还没从待结算中扣回，存在重复结算风险）。
     */
    private int checkMissingReverse() {
        String sql = "select distinct o.order_no, o.paid_amount from orders o "
                + "join settlement_record r on r.order_id = o.id and r.deleted = 0 "
                + "where o.deleted = 0 and o.status = 'REFUNDED' and r.status = 'PENDING' "
                + "limit 500";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

        int count = 0;
        for (Map<String, Object> row : rows) {
            String orderNo = (String) row.get("order_no");
            long paid = toLong(row.get("paid_amount"));
            if (recordOnce(ReconcileIssue.TYPE_MISSING_REVERSE, orderNo,
                    "PENDING", "CANCELED", paid)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 检查项 3：分账五方之和与实付不一致。
     *
     * <p>虽在 executeSplit 中已有 total_check 标记，但那是写入时的自检。
     * 对账是独立复核，能发现写入后数据被改动等异常。
     */
    private int checkSplitAmountMismatch() {
        String sql = "select s.order_no, s.snapshot_no, o.paid_amount, "
                + "(s.platform_amount + s.store_amount + s.channel_amount + s.investor_amount + s.supplier_amount) as parts "
                + "from split_snapshot s join orders o on o.id = s.order_id "
                + "where s.deleted = 0 and o.deleted = 0 "
                + "having parts <> o.paid_amount "
                + "limit 500";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

        int count = 0;
        for (Map<String, Object> row : rows) {
            String orderNo = (String) row.get("order_no");
            long paid = toLong(row.get("paid_amount"));
            long parts = toLong(row.get("parts"));
            if (recordOnce(ReconcileIssue.TYPE_SPLIT_AMOUNT_MISMATCH, orderNo,
                    "分账合计=" + parts, "实付=" + paid, Math.abs(paid - parts))) {
                count++;
            }
        }
        return count;
    }

    /**
     * 记录一条对账异常（同一 orderNo + issueType 已有 OPEN 项则跳过）。
     *
     * @return true=本次新增记录
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean recordOnce(String issueType, String orderNo,
                              String systemValue, String expectedValue, long diffAmount) {
        Long exists = reconcileIssueMapper.selectCount(new LambdaQueryWrapper<ReconcileIssue>()
                .eq(ReconcileIssue::getIssueType, issueType)
                .eq(ReconcileIssue::getOrderNo, orderNo)
                .eq(ReconcileIssue::getStatus, ReconcileIssue.STATUS_OPEN));
        if (exists != null && exists > 0) {
            return false;
        }
        ReconcileIssue issue = new ReconcileIssue();
        issue.setIssueType(issueType);
        issue.setOrderNo(orderNo);
        issue.setSystemValue(systemValue);
        issue.setThirdValue(expectedValue);
        issue.setDiffAmount(diffAmount);
        issue.setFoundTime(LocalDateTime.now());
        issue.setStatus(ReconcileIssue.STATUS_OPEN);
        reconcileIssueMapper.insert(issue);
        log.warn("对账异常已记录 type={} orderNo={} system={} expected={} diff={}",
                issueType, orderNo, systemValue, expectedValue, diffAmount);
        return true;
    }

    /** 待处理异常列表 */
    public List<ReconcileIssue> openIssues() {
        return reconcileIssueMapper.selectList(new LambdaQueryWrapper<ReconcileIssue>()
                .eq(ReconcileIssue::getStatus, ReconcileIssue.STATUS_OPEN)
                .orderByDesc(ReconcileIssue::getId));
    }

    /** 处理异常（人工确认后调用） */
    @Transactional(rollbackFor = Exception.class)
    public ReconcileIssue resolve(Long id, String status) {
        ReconcileIssue issue = reconcileIssueMapper.selectById(id);
        if (issue == null) {
            throw new com.wuling.common.exception.BusinessException(
                    com.wuling.common.api.ResultCode.NOT_FOUND, "对账异常不存在");
        }
        issue.setStatus(status);
        reconcileIssueMapper.updateById(issue);
        log.info("对账异常已处理 id={} orderNo={} status={}", id, issue.getOrderNo(), status);
        return issue;
    }

    private String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    private long toLong(Object v) {
        return v == null ? 0L : Long.parseLong(String.valueOf(v));
    }
}
