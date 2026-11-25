package com.wuling.finance.payee;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.common.security.SensitiveCrypto;
import com.wuling.finance.payee.dto.BindCardRequest;
import com.wuling.finance.port.PayoutAccountPort;
import com.wuling.subject.onboard.MerchantOnboard;
import com.wuling.subject.onboard.MerchantOnboardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结算卡管理与「收款账户端口」实现。
 *
 * <p>同时承担两个角色：
 * <ol>
 *   <li>小程序侧的结算卡 CRUD（加密存储、掩码展示、默认卡切换）；</li>
 *   <li>{@link PayoutAccountPort} 的实现，供提现服务读取收款方标识。</li>
 * </ol>
 *
 * <p><b>同名约束校验</b>：企业只能对公，个体可对公或法人卡，小微只能本人卡。
 * 这是易宝强制的规则，在本地先拦一次，避免提交后被驳回浪费审核时效。
 */
@Service
public class PayoutAccountService implements PayoutAccountPort {

    private static final Logger log = LoggerFactory.getLogger(PayoutAccountService.class);

    private final PayoutAccountMapper accountMapper;
    private final MerchantOnboardService onboardService;
    private final YeepaySettleCardPort settleCardPort;
    private final ObjectMapper objectMapper;

    public PayoutAccountService(PayoutAccountMapper accountMapper,
                                MerchantOnboardService onboardService,
                                YeepaySettleCardPort settleCardPort,
                                ObjectMapper objectMapper) {
        this.accountMapper = accountMapper;
        this.onboardService = onboardService;
        this.settleCardPort = settleCardPort;
        this.objectMapper = objectMapper;
    }

    /** 列出主体的结算卡（卡号只返回掩码）。 */
    public List<PayoutAccount> list(Long subjectId) {
        return accountMapper.selectList(new LambdaQueryWrapper<PayoutAccount>()
                .eq(PayoutAccount::getSubjectId, subjectId)
                .eq(PayoutAccount::getStatus, "ACTIVE")
                .orderByDesc(PayoutAccount::getIsDefault)
                .orderByDesc(PayoutAccount::getId));
    }

    /**
     * 绑定结算卡。
     *
     * <p>流程：校验同名约束 → 加密入库 → 调易宝绑定 → 回写 settleCardId 与校验结果。
     * 易宝绑定失败时保留本地记录并把 {@code verifyStatus} 置 FAIL，
     * 便于运营看到失败原因并指导经营者换卡，而不是让记录凭空消失。
     */
    @Transactional(rollbackFor = Exception.class)
    public PayoutAccount bind(Long subjectId, Long userId, BindCardRequest input) {
        if (input == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "结算卡信息不能为空");
        }
        if (input.getAccountName() == null || input.getAccountName().isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "开户名不能为空");
        }
        if (input.getBankCardNo() == null || input.getBankCardNo().isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "银行卡号不能为空");
        }
        // 前端可能带空格分组（如 "6222 0000 1234 5678"），统一去掉再处理
        String rawCardNo = input.getBankCardNo().replaceAll("\\s", "");
        validateAccountType(subjectId, input.getAccountType());

        // 卡号去重：同一主体不允许重复绑同一张卡
        String mask = SensitiveCrypto.maskBankCard(rawCardNo);
        PayoutAccount existing = accountMapper.selectOne(new LambdaQueryWrapper<PayoutAccount>()
                .eq(PayoutAccount::getSubjectId, subjectId)
                .eq(PayoutAccount::getBankCardMask, mask)
                .eq(PayoutAccount::getStatus, "ACTIVE")
                .last("limit 1"));
        if (existing != null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该银行卡已绑定，请勿重复添加");
        }

        boolean firstCard = list(subjectId).isEmpty();
        PayoutAccount account = new PayoutAccount();
        account.setSubjectId(subjectId);
        account.setUserId(userId);
        account.setAccountType(input.getAccountType());
        account.setAccountName(input.getAccountName());
        // 入参是明文卡号，加密由本层负责（DTO 字段名已明确表达明文语义）
        account.setBankCardNoEnc(SensitiveCrypto.encrypt(rawCardNo));
        account.setBankCardMask(mask);
        account.setBankCode(input.getBankCode());
        account.setBranchCode(input.getBranchCode());
        account.setBankName(input.getBankName());
        account.setVerifyStatus(PayoutAccount.VERIFY_PENDING);
        account.setIsDefault(firstCard ? 1 : 0);
        account.setStatus("ACTIVE");
        accountMapper.insert(account);

        // 调易宝绑定（需要明文卡号）
        try {
            MerchantOnboard onboard = onboardService.findBySubject(subjectId);
            String merchantNo = onboard == null ? null : onboard.getYeepayMerchantNo();
            YeepaySettleCardPort.BindResult result = settleCardPort.bind(
                    merchantNo, input.getAccountType(), rawCardNo,
                    input.getBankCode(), input.getBranchCode());
            if (result.accepted()) {
                account.setYeepaySettleCardId(result.settleCardId());
                account.setVerifyStatus(PayoutAccount.VERIFY_PASS);
            } else {
                account.setVerifyStatus(PayoutAccount.VERIFY_FAIL);
                account.setVerifyMsg(result.failReason());
            }
            accountMapper.updateById(account);
        } catch (Exception e) {
            // 网络异常：保持 PENDING，运营可通过「重新校验」重试
            log.error("结算卡绑定调用异常 subjectId={} err={}", subjectId, e.getMessage());
            account.setVerifyMsg("绑定请求异常，请稍后重试");
            accountMapper.updateById(account);
        }
        return account;
    }

    /** 设为默认结算卡。数据库层用生成列 + 唯一索引保证只有一张。 */
    @Transactional(rollbackFor = Exception.class)
    public void setDefault(Long subjectId, Long accountId) {
        PayoutAccount target = accountMapper.selectById(accountId);
        if (target == null || !subjectId.equals(target.getSubjectId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "结算卡不存在");
        }
        if (!PayoutAccount.VERIFY_PASS.equals(target.getVerifyStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该卡尚未通过校验，不能设为默认");
        }
        // 先清后设：分两步避免唯一索引冲突
        for (PayoutAccount account : list(subjectId)) {
            if (Integer.valueOf(1).equals(account.getIsDefault())) {
                account.setIsDefault(0);
                accountMapper.updateById(account);
            }
        }
        target.setIsDefault(1);
        accountMapper.updateById(target);
    }

    // ---------------- PayoutAccountPort ----------------

    @Override
    public Long defaultAccountId(Long subjectId) {
        PayoutAccount account = defaultAccount(subjectId);
        return account == null ? null : account.getId();
    }

    @Override
    public String yeepaySettleCardId(Long subjectId) {
        PayoutAccount account = defaultAccount(subjectId);
        return account == null ? null : account.getYeepaySettleCardId();
    }

    @Override
    public String yeepayMerchantNo(Long subjectId) {
        return onboardService.yeepayMerchantNo(subjectId);
    }

    /**
     * 收款账户快照（JSON）。
     *
     * <p>提现申请时固化写入 {@code withdrawal.payee_snapshot}，
     * 之后经营者换卡不影响历史提现单的凭证。只含掩码，不含明文卡号。
     */
    @Override
    public String snapshot(Long subjectId) {
        PayoutAccount account = defaultAccount(subjectId);
        if (account == null) {
            return null;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("accountId", account.getId());
        snapshot.put("accountName", account.getAccountName());
        snapshot.put("cardMask", account.getBankCardMask());
        snapshot.put("bankName", account.getBankName());
        snapshot.put("accountType", account.getAccountType());
        snapshot.put("yeepaySettleCardId", account.getYeepaySettleCardId());
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            log.warn("收款账户快照序列化失败 subjectId={} err={}", subjectId, e.getMessage());
            return null;
        }
    }

    private PayoutAccount defaultAccount(Long subjectId) {
        if (subjectId == null) {
            return null;
        }
        PayoutAccount account = accountMapper.selectOne(new LambdaQueryWrapper<PayoutAccount>()
                .eq(PayoutAccount::getSubjectId, subjectId)
                .eq(PayoutAccount::getIsDefault, 1)
                .eq(PayoutAccount::getStatus, "ACTIVE")
                .last("limit 1"));
        if (account != null) {
            return account;
        }
        // 兜底：没有默认卡时取最新一张已校验的卡（避免运营忘了设默认导致提现不可用）
        return accountMapper.selectOne(new LambdaQueryWrapper<PayoutAccount>()
                .eq(PayoutAccount::getSubjectId, subjectId)
                .eq(PayoutAccount::getVerifyStatus, PayoutAccount.VERIFY_PASS)
                .eq(PayoutAccount::getStatus, "ACTIVE")
                .orderByDesc(PayoutAccount::getId)
                .last("limit 1"));
    }

    /**
     * 同名约束校验。
     *
     * <p>易宝规则：企业只能绑对公；个体可对公或法人借记卡；小微只能本人借记卡。
     * 本地先拦一次，避免提交后被易宝驳回浪费审核时效。
     */
    private void validateAccountType(Long subjectId, String accountType) {
        MerchantOnboard onboard = onboardService.findBySubject(subjectId);
        String signType = onboard == null ? null : onboard.getSignType();
        if (signType == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请先提交入驻申请，再绑定结算卡");
        }
        boolean wantPublic = PayoutAccount.TYPE_PUBLIC.equals(accountType);
        if (MerchantOnboard.SIGN_ENTERPRISE.equals(signType) && !wantPublic) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "企业主体只能绑定对公账户");
        }
        if (MerchantOnboard.SIGN_MICRO.equals(signType) && wantPublic) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "自然人主体只能绑定本人借记卡（对私）");
        }
        if (!wantPublic && !PayoutAccount.TYPE_PRIVATE.equals(accountType)) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "账户类型必须为 PUBLIC 或 PRIVATE");
        }
    }
}
