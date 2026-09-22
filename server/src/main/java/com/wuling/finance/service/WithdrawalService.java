package com.wuling.finance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wuling.common.api.PageResult;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.finance.entity.FundFlow;
import com.wuling.finance.entity.SubjectAccount;
import com.wuling.finance.entity.Withdrawal;
import com.wuling.finance.mapper.FundFlowMapper;
import com.wuling.finance.mapper.SubjectAccountMapper;
import com.wuling.finance.mapper.WithdrawalMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 提现闭环。
 * 规则（对齐 user-h5/data/role-mock.js withdrawRule）：
 * - 单笔 ≤ 即时额度（默认 100 元）小额即时到账，无需人工审核；
 * - 超过即时额度需后台审核，审核通过后出款；
 * - 失败或驳回自动解冻对应金额；
 * - 申请时冻结金额，成功扣减，失败解冻。
 */
@Service
public class WithdrawalService {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 即时到账额度（分）：100 元 */
    public static final long INSTANT_LIMIT = 10000L;

    public static final String APPLIED = "APPLIED";
    public static final String AUDITING = "AUDITING";
    public static final String APPROVED = "APPROVED";
    public static final String PAID = "PAID";
    public static final String REJECTED = "REJECTED";
    public static final String FAILED = "FAILED";

    private final WithdrawalMapper withdrawalMapper;
    private final SubjectAccountMapper subjectAccountMapper;
    private final FundFlowMapper fundFlowMapper;
    private final LedgerService ledgerService;

    public WithdrawalService(WithdrawalMapper withdrawalMapper,
                             SubjectAccountMapper subjectAccountMapper,
                             FundFlowMapper fundFlowMapper,
                             LedgerService ledgerService) {
        this.withdrawalMapper = withdrawalMapper;
        this.subjectAccountMapper = subjectAccountMapper;
        this.fundFlowMapper = fundFlowMapper;
        this.ledgerService = ledgerService;
    }

    /** 申请提现：冻结金额，小额即时到账，大额进入审核 */
    @Transactional(rollbackFor = Exception.class)
    public Withdrawal apply(Long userId, Long subjectId, String roleType, long amount) {
        if (amount <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "提现金额必须大于 0");
        }
        // 确保账户存在（并发安全见 LedgerService.ensureAccount）
        SubjectAccount account = ledgerService.ensureAccount(subjectId);

        // 并发安全（第 0 期加固）：冻结改为原子 UPDATE
        // `available_balance = available_balance - ? where available_balance >= ?`，
        // 把「校验余额充足 + 扣减」合并为单条语句，由 InnoDB 行锁串行，
        // 并发提现时不会出现超提。
        if (subjectAccountMapper.freeze(subjectId, amount) == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "可提现余额不足");
        }
        long balanceAfter = (account.getAvailableBalance() == null ? 0L : account.getAvailableBalance()) - amount;
        writeFlow(subjectId, roleType, "FREEZE", "out", amount, null, balanceAfter, "提现申请冻结");

        Withdrawal w = new Withdrawal();
        w.setWithdrawNo(nextNo());
        w.setUserId(userId);
        w.setSubjectId(subjectId);
        w.setRoleType(roleType);
        w.setAmount(amount);
        w.setFee(0L);
        w.setApplyTime(LocalDateTime.now());

        if (amount <= INSTANT_LIMIT) {
            // 小额即时到账
            w.setStatus(PAID);
            w.setReviewTime(LocalDateTime.now());
            w.setPayTime(LocalDateTime.now());
        } else {
            w.setStatus(APPLIED);
        }
        withdrawalMapper.insert(w);

        if (PAID.equals(w.getStatus())) {
            settlePaid(w, account);
        }
        log.info("withdrawal apply no={} amount={} status={}", w.getWithdrawNo(), amount, w.getStatus());
        return w;
    }

    /** 后台审核：通过则出款，驳回则解冻 */
    @Transactional(rollbackFor = Exception.class)
    public Withdrawal review(Long id, boolean approve, String reason) {
        Withdrawal w = withdrawalMapper.selectById(id);
        if (w == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "提现申请不存在");
        }
        if (!APPLIED.equals(w.getStatus()) && !AUDITING.equals(w.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该提现申请已处理，不能重复审核");
        }
        SubjectAccount account = ledgerService.ensureAccount(w.getSubjectId());
        if (approve) {
            long available = account.getAvailableBalance() == null ? 0L : account.getAvailableBalance();
            long frozen = account.getFrozenBalance() == null ? 0L : account.getFrozenBalance();
            if (frozen < w.getAmount()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "冻结金额异常，无法出款");
            }
            w.setStatus(PAID);
            w.setReviewTime(LocalDateTime.now());
            w.setPayTime(LocalDateTime.now());
            withdrawalMapper.updateById(w);
            settlePaid(w, account);
        } else {
            w.setStatus(REJECTED);
            w.setReviewTime(LocalDateTime.now());
            w.setFailureReason(reason);
            withdrawalMapper.updateById(w);
            unfreeze(w, account, "审核驳回，金额已解冻");
        }
        return w;
    }

    /** 出款失败：解冻 */
    @Transactional(rollbackFor = Exception.class)
    public Withdrawal markFailed(Long id, String reason) {
        Withdrawal w = withdrawalMapper.selectById(id);
        if (w == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "提现申请不存在");
        }
        if (PAID.equals(w.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "已出款，不能标记失败");
        }
        SubjectAccount account = ledgerService.ensureAccount(w.getSubjectId());
        w.setStatus(FAILED);
        w.setFailureReason(reason);
        withdrawalMapper.updateById(w);
        unfreeze(w, account, "出款失败，金额已解冻");
        return w;
    }

    /** 按 ID 查询（供审计记录变更前状态使用） */
    public Withdrawal getById(Long id) {
        return id == null ? null : withdrawalMapper.selectById(id);
    }

    public PageResult<Withdrawal> page(long current, long size, String status) {
        LambdaQueryWrapper<Withdrawal> query = new LambdaQueryWrapper<Withdrawal>().orderByDesc(Withdrawal::getId);
        if (status != null && !status.isBlank()) {
            query.eq(Withdrawal::getStatus, status.toUpperCase());
        }
        Page<Withdrawal> page = withdrawalMapper.selectPage(new Page<>(current, size), query);
        return PageResult.of(page.getRecords(), page.getCurrent(), page.getSize(), page.getTotal());
    }

    /** 出款成功：从冻结中扣减（原子 SQL，防并发重复出款） */
    private void settlePaid(Withdrawal w, SubjectAccount account) {
        if (subjectAccountMapper.settleWithdraw(w.getSubjectId(), w.getAmount()) == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "冻结金额异常，无法出款");
        }
        writeFlow(w.getSubjectId(), w.getRoleType(), "WITHDRAW", "out", w.getAmount(), null,
                account.getAvailableBalance(), "提现出款");
    }

    /** 失败/驳回：冻结金额退回可用（原子 SQL） */
    private void unfreeze(Withdrawal w, SubjectAccount account, String remark) {
        if (subjectAccountMapper.unfreeze(w.getSubjectId(), w.getAmount()) == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "解冻失败：冻结金额不足");
        }
        writeFlow(w.getSubjectId(), w.getRoleType(), "UNFREEZE", "in", w.getAmount(), null,
                account.getAvailableBalance(), remark);
    }

    private void writeFlow(Long subjectId, String roleType, String type, String direction,
                           long amount, String orderNo, long balanceAfter, String remark) {
        FundFlow flow = new FundFlow();
        flow.setFlowNo("FF" + System.currentTimeMillis() % 100000000L
                + String.format("%03d", ThreadLocalRandom.current().nextInt(1000)));
        flow.setSubjectId(subjectId);
        flow.setRoleType(roleType);
        flow.setType(type);
        flow.setDirection(direction);
        flow.setAmount(amount);
        flow.setOrderNo(orderNo);
        flow.setBalanceAfter(balanceAfter);
        flow.setRemark(remark);
        fundFlowMapper.insert(flow);
    }

    private String nextNo() {
        return "WD" + LocalDateTime.now().format(FMT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }
}
