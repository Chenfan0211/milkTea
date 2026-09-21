package com.wuling.subject.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.common.api.PageResult;
import com.wuling.subject.dto.AdminStoreDTO;
import com.wuling.subject.dto.AppStoreDTO;
import com.wuling.subject.entity.BizSubject;
import com.wuling.subject.entity.StoreProfile;
import com.wuling.subject.mapper.BizSubjectMapper;
import com.wuling.subject.mapper.StoreProfileMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class StoreService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final BizSubjectMapper bizSubjectMapper;
    private final StoreProfileMapper storeProfileMapper;
    private final ObjectMapper objectMapper;

    public StoreService(BizSubjectMapper bizSubjectMapper,
                        StoreProfileMapper storeProfileMapper,
                        ObjectMapper objectMapper) {
        this.bizSubjectMapper = bizSubjectMapper;
        this.storeProfileMapper = storeProfileMapper;
        this.objectMapper = objectMapper;
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
            dto.setLatitude(profile.getLatitude());
            dto.setLongitude(profile.getLongitude());
            dto.setStoreType(profile.getStoreType());
            dto.setBusinessStatus(profile.getBusinessStatus());
            dto.setManager(profile.getManager());
            dto.setInvestorSubjectId(profile.getInvestorSubjectId());
            dto.setBusinessHours(profile.getBusinessHours());
            dto.setModes(parseStringList(profile.getModes()));
            dto.setPromotion(profile.getPromotion());
            dto.setQueueCount(profile.getQueueCount());
            result.add(dto);
        }
        return result;
    }

    public PageResult<AdminStoreDTO> pageAdminStores(long current, long size, String search) {
        LambdaQueryWrapper<BizSubject> query = new LambdaQueryWrapper<BizSubject>()
                .eq(BizSubject::getSubjectType, "STORE")
                .orderByAsc(BizSubject::getId);
        if (StringUtils.hasText(search)) {
            query.and(w -> w.like(BizSubject::getCode, search).or().like(BizSubject::getName, search));
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
        }
        return dto;
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
}
