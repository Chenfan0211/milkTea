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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class StoreService {

    private static final Logger log = LoggerFactory.getLogger(StoreService.class);

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final BizSubjectMapper bizSubjectMapper;
    private final StoreProfileMapper storeProfileMapper;
    private final ObjectMapper objectMapper;
    private final TradeOrderQueryPort tradeOrderQueryPort;
    private final JdbcTemplate jdbcTemplate;

    public StoreService(BizSubjectMapper bizSubjectMapper,
                        StoreProfileMapper storeProfileMapper,
                        ObjectMapper objectMapper,
                        TradeOrderQueryPort tradeOrderQueryPort,
                        JdbcTemplate jdbcTemplate) {
        this.bizSubjectMapper = bizSubjectMapper;
        this.storeProfileMapper = storeProfileMapper;
        this.objectMapper = objectMapper;
        this.tradeOrderQueryPort = tradeOrderQueryPort;
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AppStoreDTO> listAppStores() {
        List<BizSubject> subjects = bizSubjectMapper.selectList(new LambdaQueryWrapper<BizSubject>()
                .eq(BizSubject::getSubjectType, "STORE")
                .orderByAsc(BizSubject::getId));
        if (subjects.isEmpty()) {
            return List.of();
        }

        // ---------- 批量预取，消灭 N+1 ----------
        // 旧实现对每家门店循环：查 profile + 查活动城市 + 查 region + 跨服务查队列，
        // 门店数一多就是 O(N) 次 SQL 与 O(N) 次 HTTP（线上实测 6~8s）。
        // 这里统一收敛为「4 次批量查询 + 内存拼装」，与门店数无关。
        List<Long> subjectIds = subjects.stream().map(BizSubject::getId).toList();

        // 1) 门店档案：一次 IN 查询
        Map<Long, StoreProfile> profileBySubject = storeProfileMapper.selectList(
                        new LambdaQueryWrapper<StoreProfile>().in(StoreProfile::getSubjectId, subjectIds))
                .stream()
                .collect(Collectors.toMap(StoreProfile::getSubjectId, p -> p, (a, b) -> a));

        // 2) 活动城市白名单：一次 IN 查询，得到 enabled 的 id 集合
        Set<Long> activityCityIds = profileBySubject.values().stream()
                .map(StoreProfile::getActivityCityId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> enabledActivityCityIds = activityCityIds.isEmpty()
                ? Set.of()
                : new HashSet<>(jdbcTemplate.queryForList(
                        "select id from activity_city where status = 'enabled' and deleted = 0 and id in ("
                                + placeholders(activityCityIds.size()) + ")",
                        Long.class, activityCityIds.toArray()));

        // 3) 城市编码：一次 IN 查询
        Set<Long> cityIds = profileBySubject.values().stream()
                .map(StoreProfile::getCityId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> cityCodeById = new HashMap<>();
        if (!cityIds.isEmpty()) {
            for (Map<String, Object> row : jdbcTemplate.queryForList(
                    "select id, code from region where deleted = 0 and id in ("
                            + placeholders(cityIds.size()) + ")", cityIds.toArray())) {
                Object id = row.get("id");
                Object code = row.get("code");
                if (id instanceof Number n && code != null) {
                    cityCodeById.put(n.longValue(), String.valueOf(code));
                }
            }
        }

        // 4) 门店队列数：一次跨服务调用（trade-service 内部 GROUP BY）
        Map<Long, Long> queueByStore = countPendingQueueBatch(subjectIds);

        List<AppStoreDTO> result = new ArrayList<>(subjects.size());
        for (BizSubject subject : subjects) {
            StoreProfile profile = profileBySubject.get(subject.getId());
            if (profile == null) {
                continue;
            }
            // 活动城市白名单：门店必须绑定 enabled 的活动城市才对外返回，
            // 杜绝「有门店但城市未开通」的矛盾（V71）。
            if (profile.getActivityCityId() == null
                    || !enabledActivityCityIds.contains(profile.getActivityCityId())) {
                continue;
            }
            // 后台「停用」只把 business_status 写成 closed，主体仍是 active。
            // 消费者列表只返回营业中的店；null 与 closed 都不算营业。
            if (!"open".equals(profile.getBusinessStatus())) {
                continue;
            }
            AppStoreDTO dto = new AppStoreDTO();
            dto.setId(subject.getId());
            dto.setCode(subject.getCode());
            dto.setName(subject.getName());
            dto.setCity(profile.getCity());
            dto.setCityId(profile.getCityId());
            dto.setCityCode(profile.getCityId() == null ? null : cityCodeById.get(profile.getCityId()));
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
            // 队列数实时统计：该门店已支付未核销（PAID）的订单数；查不到则为 0，前端不展示
            dto.setQueueCount(queueByStore.getOrDefault(subject.getId(), 0L).intValue());
            result.add(dto);
        }
        return result;
    }

    /** 生成 IN 占位符（?, ?, ...），供 JdbcTemplate 可变参数使用。 */
    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
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
            dto.setCityId(profile.getCityId());
            dto.setActivityCityId(profile.getActivityCityId());
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
        profile.setCityId(upsert.getCityId());
        if (upsert.getActivityCityId() != null) profile.setActivityCityId(upsert.getActivityCityId());
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

    /**
     * 门店排队件数：近 1 小时「已支付待核销」的商品总件数，用于点单页「前方N杯制作中」。
     *
     * <p>口径由 trade-service 的 /internal/store-queue-count 计算，本方法只做透传。
     * 注意：这**不是**「已核销未取餐」——奶茶核销即取餐，complete_time 永远为空，
     * 按 COMPLETED 统计会把历史全部已核销订单当成在制（历史事故：某店显示「前方28杯」）。
     *
     * <p>下游不可用时返回 0（前端对 0 不展示该文案）；此处必须留日志，
     * 否则线上故障只表现为「数字突然变 0」，无法定位。
     */
    private Integer countPendingQueue(Long storeSubjectId) {
        try {
            return (int) tradeOrderQueryPort.countStoreQueueItems(storeSubjectId);
        } catch (Exception e) {
            log.warn("门店排队件数查询失败，回退为 0 storeSubjectId={} err={}", storeSubjectId, e.getMessage());
            return 0;
        }
    }

    /**
     * 批量门店排队件数：一次跨服务调用取回全部门店，替代逐店调用。
     *
     * <p>下游不可用时整体回退为空 Map，各门店按 0 处理（前端对 0 不展示该文案）。
     */
    private Map<Long, Long> countPendingQueueBatch(List<Long> storeSubjectIds) {
        try {
            return tradeOrderQueryPort.countStoreQueueItemsBatch(storeSubjectIds);
        } catch (Exception e) {
            log.warn("批量查询门店排队件数失败，回退为空 storeCount={} err={}",
                    storeSubjectIds == null ? 0 : storeSubjectIds.size(), e.getMessage());
            return Map.of();
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
        private Long cityId;
        private Long activityCityId;
        private String manager;
        private String location;
        private String phone;
        private String storeType;
        private String businessStatus;
        private BigDecimal latitude;
        private BigDecimal longitude;
    }
}
