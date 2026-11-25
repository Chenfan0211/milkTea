package com.wuling.subject.onboard;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.common.api.PageResult;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.common.security.SensitiveCrypto;
import com.wuling.subject.onboard.dto.MerchantOnboardRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 经营者商户入驻编排。
 *
 * <p><b>入驻 ≠ 收款前置</b>：钱进入平台待分账账户后不会丢（易宝不会自动分账，
 * 资金一直停留），经营者可以先接单赚钱，想提现时再完成入驻。
 * 提现时会做闸门校验并给出补办指引。
 *
 * <p><b>签约在这一步</b>：易宝没有独立的「签约接口」。入驻提交 → 易宝审核
 * → 入网结果通知下发<b>电子签章地址</b> → 经营者签署 → 业务开通。
 * 故 {@code status=COMPLETED} 已隐含「已签约」。
 *
 * <p><b>与交易服务的边界</b>：本服务只管「入驻状态与资料」，
 * 真正调易宝接口的是 trade-service 的 {@code YeepayOnboardService}。
 * 这样易宝 SDK 只出现在一个模块里。
 */
@Service
public class MerchantOnboardService {

    private static final Logger log = LoggerFactory.getLogger(MerchantOnboardService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final MerchantOnboardMapper onboardMapper;
    private final MerchantMaterialMapper materialMapper;
    private final YeepayOnboardPort yeepayOnboardPort;

    public MerchantOnboardService(MerchantOnboardMapper onboardMapper,
                                  MerchantMaterialMapper materialMapper,
                                  YeepayOnboardPort yeepayOnboardPort) {
        this.onboardMapper = onboardMapper;
        this.materialMapper = materialMapper;
        this.yeepayOnboardPort = yeepayOnboardPort;
    }

    /**
     * 是否已入驻完成（提现闸门用）。
     *
     * <p>条件：存在记录且 {@code status=COMPLETED} 且已拿到易宝商户编号。
     * 只有拿到商户编号才能作为分账接收方，缺一不可。
     */
    public boolean isOnboarded(Long subjectId) {
        MerchantOnboard onboard = findBySubject(subjectId);
        return onboard != null
                && MerchantOnboard.STATUS_COMPLETED.equals(onboard.getApplicationStatus())
                && onboard.getYeepayMerchantNo() != null
                && !onboard.getYeepayMerchantNo().isBlank();
    }

    /** 查询入驻状态（小程序展示用）。 */
    public MerchantOnboard findBySubject(Long subjectId) {
        if (subjectId == null) {
            return null;
        }
        return onboardMapper.selectOne(new LambdaQueryWrapper<MerchantOnboard>()
                .eq(MerchantOnboard::getSubjectId, subjectId)
                .last("limit 1"));
    }

    /** 按入网请求号查（通知落库后需要反查主体 id）。 */
    public MerchantOnboard findBySubjectIdByRequestNo(String requestNo) {
        if (requestNo == null) {
            return null;
        }
        return onboardMapper.selectOne(new LambdaQueryWrapper<MerchantOnboard>()
                .eq(MerchantOnboard::getRequestNo, requestNo)
                .last("limit 1"));
    }

    /** 易宝子商户编号（分账 ledgerNo）。未入驻返回 null。 */
    public String yeepayMerchantNo(Long subjectId) {
        MerchantOnboard onboard = findBySubject(subjectId);
        return onboard == null ? null : onboard.getYeepayMerchantNo();
    }

    /**
     * 提交入驻。
     *
     * <p>幂等：同一主体已有非驳回状态的记录时直接返回现状，
     * 避免重复提交产生多个申请单（易宝侧同一请求号也不允许重复发起）。
     */
    @Transactional(rollbackFor = Exception.class)
    public MerchantOnboard submit(Long subjectId, Long userId, MerchantOnboardRequest request) {
        if (request == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "入驻资料不能为空");
        }
        validate(request);

        MerchantOnboard existing = findBySubject(subjectId);
        if (existing != null
                && !MerchantOnboard.STATUS_REVIEW_BACK.equals(existing.getApplicationStatus())) {
            log.info("入驻已存在且非驳回状态，直接返回 subjectId={} status={}",
                    subjectId, existing.getApplicationStatus());
            return existing;
        }

        // 保存/更新资料（敏感字段加密 + 掩码）
        saveMaterial(subjectId, request);

        String requestNo = nextRequestNo();
        MerchantOnboard onboard = existing == null ? new MerchantOnboard() : existing;
        onboard.setSubjectId(subjectId);
        onboard.setUserId(userId);
        onboard.setRequestNo(requestNo);
        onboard.setSignType(request.getSignType());
        onboard.setBusinessRole("SETTLED_MERCHANT");
        onboard.setApplicationStatus(MerchantOnboard.STATUS_REVIEWING);
        onboard.setRejectReason(null);
        onboard.setSignUrl(null);
        onboard.setSignedTime(null);
        onboard.setRetryCount(0);
        if (existing == null) {
            onboardMapper.insert(onboard);
        } else {
            onboardMapper.updateById(onboard);
        }

        // 调易宝提交入网申请（失败不回滚本地记录：资料已存，运营可重试）
        try {
            YeepayOnboardPort.OnboardResult result = yeepayOnboardPort.submit(onboard, request);
            if (result.accepted()) {
                onboard.setApplicationNo(result.applicationNo());
                onboard.setYeepayMerchantNo(result.merchantNo());
                if (result.applicationStatus() != null) {
                    onboard.setApplicationStatus(result.applicationStatus());
                }
                onboardMapper.updateById(onboard);
            } else {
                log.warn("易宝入驻提交未受理 subjectId={} reason={}", subjectId, result.failReason());
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "提交入驻失败：" + result.failReason());
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // 网络异常：本地记录保留在 REVIEWING，运营可重新查询进度
            log.error("易宝入驻提交异常 subjectId={} err={}", subjectId, e.getMessage(), e);
            throw new BusinessException(ResultCode.ERROR,
                    "提交入驻请求异常，请稍后重试或联系客服：" + e.getMessage());
        }
        return onboard;
    }

    /**
     * 应用易宝「入网结果通知」。
     *
     * <p>通知可能多次到达（易宝会重试 9 次直到收到 SUCCESS 应答），
     * 故本方法必须幂等：只按状态推进，不做重复副作用。
     */
    @Transactional(rollbackFor = Exception.class)
    public void applyNotify(String requestNo, String applicationStatus, String merchantNo,
                            String signUrl, String rejectReason, String raw) {
        MerchantOnboard onboard = onboardMapper.selectOne(new LambdaQueryWrapper<MerchantOnboard>()
                .eq(MerchantOnboard::getRequestNo, requestNo)
                .last("limit 1"));
        if (onboard == null) {
            log.warn("入网通知找不到入驻记录 requestNo={}", requestNo);
            return;
        }
        onboard.setNotifyRaw(raw);
        if (applicationStatus != null) {
            onboard.setApplicationStatus(applicationStatus);
        }
        if (merchantNo != null && !merchantNo.isBlank()) {
            onboard.setYeepayMerchantNo(merchantNo);
        }
        if (signUrl != null && !signUrl.isBlank()) {
            onboard.setSignUrl(signUrl);
        }
        if (rejectReason != null && !rejectReason.isBlank()) {
            onboard.setRejectReason(rejectReason);
        }
        onboardMapper.updateById(onboard);
        log.info("入驻状态更新 subjectId={} status={} merchantNo={}",
                onboard.getSubjectId(), applicationStatus, merchantNo);
    }

    /** 经营者完成签署（前端回调告知）。 */
    @Transactional(rollbackFor = Exception.class)
    public void markSigned(Long subjectId) {
        MerchantOnboard onboard = findBySubject(subjectId);
        if (onboard == null) {
            return;
        }
        if (onboard.getSignedTime() == null) {
            onboard.setSignedTime(LocalDateTime.now());
            onboardMapper.updateById(onboard);
        }
    }

    /** 主动查询进度（补偿通知丢失）。 */
    @Transactional(rollbackFor = Exception.class)
    public MerchantOnboard refresh(Long subjectId) {
        MerchantOnboard onboard = findBySubject(subjectId);
        if (onboard == null || onboard.getRequestNo() == null) {
            return onboard;
        }
        try {
            YeepayOnboardPort.OnboardQueryResult result = yeepayOnboardPort.query(onboard.getRequestNo());
            if (result != null) {
                if (result.applicationStatus() != null) {
                    onboard.setApplicationStatus(result.applicationStatus());
                }
                if (result.merchantNo() != null) {
                    onboard.setYeepayMerchantNo(result.merchantNo());
                }
                if (result.rejectReason() != null) {
                    onboard.setRejectReason(result.rejectReason());
                }
            }
            onboard.setRetryCount((onboard.getRetryCount() == null ? 0 : onboard.getRetryCount()) + 1);
            onboard.setLastQueryTime(LocalDateTime.now());
            onboardMapper.updateById(onboard);
        } catch (Exception e) {
            log.warn("查询入驻进度失败 subjectId={} err={}", subjectId, e.getMessage());
        }
        return onboard;
    }

    /** 后台：入驻列表。 */
    public PageResult<MerchantOnboard> page(long current, long size, String status) {
        LambdaQueryWrapper<MerchantOnboard> query = new LambdaQueryWrapper<MerchantOnboard>()
                .orderByDesc(MerchantOnboard::getId);
        if (status != null && !status.isBlank()) {
            query.eq(MerchantOnboard::getApplicationStatus, status.toUpperCase());
        }
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<MerchantOnboard> page =
                onboardMapper.selectPage(
                        new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(current, size), query);
        return PageResult.of(page.getRecords(), page.getCurrent(), page.getSize(), page.getTotal());
    }

    /** 后台：查询某主体的入驻资料（证件号与手机号返回掩码）。 */
    public MerchantMaterial findMaterial(Long subjectId) {
        return materialMapper.selectOne(new LambdaQueryWrapper<MerchantMaterial>()
                .eq(MerchantMaterial::getSubjectId, subjectId)
                .last("limit 1"));
    }

    private void validate(MerchantOnboardRequest request) {
        String signType = request.getSignType();
        if (signType == null
                || !(MerchantOnboard.SIGN_ENTERPRISE.equals(signType)
                || MerchantOnboard.SIGN_INDIVIDUAL.equals(signType)
                || MerchantOnboard.SIGN_MICRO.equals(signType))) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "主体类型必须为 ENTERPRISE / INDIVIDUAL / MICRO");
        }
        requireText(request.getLegalName(), "法人/经营者姓名");
        requireText(request.getLegalLicenceNo(), "证件号码");
        requireText(request.getSignName(), "商户签约名");
        requireText(request.getContactMobile(), "联系人手机号");
        // 企业/个体必须有营业执照；小微只要身份证
        if (!MerchantOnboard.SIGN_MICRO.equals(signType)) {
            requireText(request.getLicenceUrl(), "营业执照");
            requireText(request.getLicenceNo(), "统一社会信用代码");
        }
        if (request.getLegalLicenceFrontUrl() == null || request.getLegalLicenceFrontUrl().isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请上传证件人像面");
        }
    }

    private void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, fieldName + "不能为空");
        }
    }

    private void saveMaterial(Long subjectId, MerchantOnboardRequest request) {
        MerchantMaterial material = materialMapper.selectOne(new LambdaQueryWrapper<MerchantMaterial>()
                .eq(MerchantMaterial::getSubjectId, subjectId)
                .last("limit 1"));
        boolean insert = material == null;
        if (insert) {
            material = new MerchantMaterial();
            material.setSubjectId(subjectId);
        }
        material.setLegalName(request.getLegalName());
        material.setLegalLicenceNoEnc(SensitiveCrypto.encrypt(request.getLegalLicenceNo()));
        material.setLegalLicenceMask(SensitiveCrypto.maskIdCard(request.getLegalLicenceNo()));
        material.setLegalLicenceFrontUrl(request.getLegalLicenceFrontUrl());
        material.setLegalLicenceBackUrl(request.getLegalLicenceBackUrl());
        material.setLicenceUrl(request.getLicenceUrl());
        material.setLicenceNo(request.getLicenceNo());
        material.setSignName(request.getSignName());
        material.setShortName(request.getShortName());
        material.setContactName(request.getContactName());
        material.setContactMobileEnc(SensitiveCrypto.encrypt(request.getContactMobile()));
        material.setContactMobileMask(SensitiveCrypto.maskMobile(request.getContactMobile()));
        material.setContactEmail(request.getContactEmail());
        material.setProvince(request.getProvince());
        material.setCity(request.getCity());
        material.setDistrict(request.getDistrict());
        material.setAddress(request.getAddress());
        if (insert) {
            materialMapper.insert(material);
        } else {
            materialMapper.updateById(material);
        }
    }

    private String nextRequestNo() {
        return "OB" + LocalDateTime.now().format(FMT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }
}
