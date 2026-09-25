package com.wuling.subject.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.common.api.PageResult;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.subject.dto.AdminStoreDTO;
import com.wuling.subject.dto.AppStoreDTO;
import com.wuling.subject.entity.BizSubject;
import com.wuling.subject.entity.StoreProfile;
import com.wuling.subject.mapper.BizSubjectMapper;
import com.wuling.subject.mapper.StoreProfileMapper;
import com.wuling.finance.port.TradeOrderQueryPort;
import lombok.Data;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class StoreService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final BizSubjectMapper bizSubjectMapper;
    private final StoreProfileMapper storeProfileMapper;
    private final ObjectMapper objectMapper;
    private final TradeOrderQueryPort tradeOrderQueryPort;

    public StoreService(BizSubjectMapper bizSubjectMapper,
                        StoreProfileMapper storeProfileMapper,
                        ObjectMapper objectMapper,
                        TradeOrderQueryPort tradeOrderQueryPort) {
        this.bizSubjectMapper = bizSubjectMapper;
        this.storeProfileMapper = storeProfileMapper;
        this.objectMapper = objectMapper;
        this.tradeOrderQueryPort = tradeOrderQueryPort;
    }

    public List<AppStoreDTO> listAppStores() {
        List<BizSubject> subjects = bizSubjectMapper.selectList(new LambdaQueryWrapper<BizSubject>()
                .eq(BizSubject::getSubjectType, "STORE")
                .orderByAsc(BizSubject::getId));
        List<AppStoreDTO> result = new ArrayList<>();
        for (BizSubject subject : subjects) {
            StoreProfile profile = findProfile(subject.getId());
            if (profile == null) {
                continue;
            }
            AppStoreDTO dto = new AppStoreDTO();
            dto.setId(subject.getId());
            dto.setCode(subject.getCode());
            dto.setName(subject.getName());
            dto.setCity(profile.getCity());
            dto.setAddress(profile.getAddress());
            dto.setPhone(profile.getPhone());
            dto.setStoreType(profile.getStoreType());
            dto.setAddress(profile.getAddress());
            dto.setLatitude(profile.getLatitude());
            dto.setLongitude(profile.getLongitude());
            dto.setStoreType(profile.getStoreType());
            dto.setBusinessStatus(profile.getBusinessStatus());
            dto.setManager(profile.getManager());
            dto.setInvestorSubjectId(profile.getInvestorSubjectId());
            dto.setBusinessHours(profile.getBusinessHours());
            dto.setModes(parseStringList(profile.getModes()));
            dto.setPromotion(profile.getPromotion());
            // 队列数实时统计：该门店已支付未核销（PAID）的订单数；查不到则为 0，前端不展示
            dto.setQueueCount(countPendingQueue(subject.getId()));
            result.add(dto);
        }
        return result;
    }

    public PageResult<AdminStoreDTO> pageAdminStores(long current, long size, String search, String status) {
        LambdaQueryWrapper<BizSubject> query = new LambdaQueryWrapper<BizSubject>()
                .eq(BizSubject::getSubjectType, "STORE")
                .orderByAsc(BizSubject::getId);
        if (StringUtils.hasText(search)) {
            query.and(w -> w.like(BizSubject::getCode, search).or().like(BizSubject::getName, search));
        }
        if (StringUtils.hasText(status)) {
            String normalized = status.trim();
            if (!"open".equals(normalized) && !"closed".equals(normalized)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "营业状态参数不合法");
            }
            query.apply("id in (select subject_id from store_profile where business_status = {0} and deleted = 0)", normalized);
        }
        Page<BizSubject> page = bizSubjectMapper.selectPage(new Page<>(current, size), query);
        List<AdminStoreDTO> records = page.getRecords().stream().map(this::toAdminStore).toList();
        return PageResult.of(records, page.getCurrent(), page.getSize(), page.getTotal());
    }

    private AdminStoreDTO toAdminStore(BizSubject subject) {
        AdminStoreDTO dto = new AdminStoreDTO();
        dto.setId(subject.getId());
        dto.setCode(subject.getCode());
        dto.setName(subject.getName());
        dto.setBoundUserId(subject.getBoundUserId());
        dto.setBoundUserName(subject.getBoundUserId() == null ? "未绑定" : "用户" + subject.getBoundUserId());
        dto.setType("store");
        dto.setCreateTime(subject.getCreateTime() == null ? null : subject.getCreateTime().format(FMT));
        StoreProfile profile = findProfile(subject.getId());
        if (profile != null) {
            dto.setCity(profile.getCity());
            dto.setBusinessStatus(profile.getBusinessStatus());
            dto.setManager(profile.getManager());
            dto.setLocation(profile.getAddress());
            if (profile.getInvestorSubjectId() != null) {
                BizSubject investor = bizSubjectMapper.selectById(profile.getInvestorSubjectId());
                dto.setInvestorName(investor == null ? "未绑定" : investor.getName());
            } else {
                dto.setInvestorName("未绑定");
            }
            dto.setInvestorSubjectId(profile.getInvestorSubjectId());
            dto.setPhone(profile.getPhone());
            dto.setStoreType(profile.getStoreType());
            dto.setAddress(profile.getAddress());
            dto.setLatitude(profile.getLatitude());
            dto.setLongitude(profile.getLongitude());
        }
        return dto;
    }

    @Transactional
    public AdminStoreDTO createAdminStore(AdminStoreUpsert upsert) {
        String nextCode = nextStoreCode();
        BizSubject subject = new BizSubject();
        subject.setCode(nextCode);
        subject.setName(upsert.getName());
        subject.setSubjectType("STORE");
        subject.setStatus("active");
        bizSubjectMapper.insert(subject);

        StoreProfile profile = storeProfileMapper.selectOne(new LambdaQueryWrapper<StoreProfile>()
                .eq(StoreProfile::getSubjectId, subject.getId()));
        if (profile == null) {
            profile = new StoreProfile();
            profile.setSubjectId(subject.getId());
        }
        applyUpsert(profile, upsert, nextCode);
        if (!StringUtils.hasText(profile.getBusinessStatus())) {
            profile.setBusinessStatus("open");
        }
        if (profile.getInvestorSubjectId() == null) {
            profile.setInvestorSubjectId(null);
        }
        if (profile.getId() == null) {
            storeProfileMapper.insert(profile);
        } else {
            storeProfileMapper.updateById(profile);
        }
        return toAdminStore(bizSubjectMapper.selectById(subject.getId()));
    }

    @Transactional
    public AdminStoreDTO updateAdminStore(long subjectId, AdminStoreUpsert upsert) {
        BizSubject subject = bizSubjectMapper.selectById(subjectId);
        if (subject == null || subject.getDeleted() != null && subject.getDeleted() == 1) {
            throw new BusinessException(ResultCode.NOT_FOUND, "门店不存在");
        }
        if (!"STORE".equals(subject.getSubjectType())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "主体不是门店");
        }
        subject.setName(upsert.getName());
        bizSubjectMapper.updateById(subject);

        StoreProfile profile = storeProfileMapper.selectOne(new LambdaQueryWrapper<StoreProfile>()
                .eq(StoreProfile::getSubjectId, subjectId));
        if (profile == null) {
            profile = new StoreProfile();
            profile.setSubjectId(subjectId);
            profile.setCode(subject.getCode());
        }
        applyUpsert(profile, upsert, subject.getCode());
        if (profile.getId() == null) {
            storeProfileMapper.insert(profile);
        } else {
            storeProfileMapper.updateById(profile);
        }
        return toAdminStore(subject);
    }

    private void applyUpsert(StoreProfile profile, AdminStoreUpsert upsert, String fallbackCode) {
        if (!StringUtils.hasText(upsert.getName())) throw new BusinessException(ResultCode.BAD_REQUEST, "名称必填");
        if (!StringUtils.hasText(upsert.getCity())) throw new BusinessException(ResultCode.BAD_REQUEST, "城市必填");
        if (!StringUtils.hasText(upsert.getManager())) throw new BusinessException(ResultCode.BAD_REQUEST, "负责人必填");
        if (!StringUtils.hasText(upsert.getLocation())) throw new BusinessException(ResultCode.BAD_REQUEST, "地址必填");
        if (!StringUtils.hasText(upsert.getPhone())) throw new BusinessException(ResultCode.BAD_REQUEST, "电话必填");
        if (!StringUtils.hasText(upsert.getStoreType())) throw new BusinessException(ResultCode.BAD_REQUEST, "门店类型必填");

        if (!StringUtils.hasText(profile.getCode())) profile.setCode(fallbackCode);
        profile.setCity(upsert.getCity());
        profile.setAddress(upsert.getLocation());
        profile.setPhone(upsert.getPhone());
        profile.setStoreType(upsert.getStoreType());
        profile.setManager(upsert.getManager());
        if (upsert.getLatitude() != null) profile.setLatitude(upsert.getLatitude());
        if (upsert.getLongitude() != null) profile.setLongitude(upsert.getLongitude());
        if (StringUtils.hasText(upsert.getBusinessStatus())) profile.setBusinessStatus(upsert.getBusinessStatus());
        else if (!StringUtils.hasText(profile.getBusinessStatus())) profile.setBusinessStatus("open");
    }

    private String nextStoreCode() {
        Long count = bizSubjectMapper.selectCount(new LambdaQueryWrapper<BizSubject>()
                .eq(BizSubject::getSubjectType, "STORE"));
        long next = (count == null ? 0 : count) + 1001;
        return "ST-" + String.format("%04d", next);
    }

    /** 门店排队件数：已核销未取餐的商品总件数（查询失败或 0 表示无需展示） */
    private Integer countPendingQueue(Long storeSubjectId) {
        try {
            return (int) tradeOrderQueryPort.countStoreQueueItems(storeSubjectId);
        } catch (Exception e) {
            return 0;
        }
    }

    private StoreProfile findProfile(Long subjectId) {
        return storeProfileMapper.selectOne(new LambdaQueryWrapper<StoreProfile>()
                .eq(StoreProfile::getSubjectId, subjectId));
    }

    private List<String> parseStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    @Data
    public static class AdminStoreUpsert {
        private String name;
        private String city;
        private String manager;
        private String location;
        private String phone;
        private String storeType;
        private String businessStatus;
        private BigDecimal latitude;
        private BigDecimal longitude;
    }
}
