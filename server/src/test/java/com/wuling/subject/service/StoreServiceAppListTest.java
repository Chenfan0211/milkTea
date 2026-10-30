package com.wuling.subject.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.finance.port.TradeOrderQueryPort;
import com.wuling.subject.dto.AppStoreDTO;
import com.wuling.subject.entity.BizSubject;
import com.wuling.subject.entity.StoreProfile;
import com.wuling.subject.mapper.BizSubjectMapper;
import com.wuling.subject.mapper.StoreProfileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StoreServiceAppListTest {

    private BizSubjectMapper bizSubjectMapper;
    private StoreProfileMapper storeProfileMapper;
    private JdbcTemplate jdbcTemplate;
    private StoreService service;

    @BeforeEach
    void setUp() {
        bizSubjectMapper = mock(BizSubjectMapper.class);
        storeProfileMapper = mock(StoreProfileMapper.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        TradeOrderQueryPort tradeOrderQueryPort = mock(TradeOrderQueryPort.class);
        when(tradeOrderQueryPort.countStoreQueueItemsBatch(any())).thenReturn(java.util.Map.of());
        when(jdbcTemplate.queryForList(contains("activity_city"), eq(Long.class), any()))
                .thenReturn(List.of(9L));
        service = new StoreService(bizSubjectMapper, storeProfileMapper, new ObjectMapper(),
                tradeOrderQueryPort, jdbcTemplate);
    }

    @Test
    void appStoreListSkipsClosedAndMissingBusinessStatus() {
        when(bizSubjectMapper.selectList(any())).thenReturn(List.of(
                subject(1L, "ST-OPEN"),
                subject(2L, "ST-CLOSED"),
                subject(3L, "ST-NULL")));
        when(storeProfileMapper.selectList(any())).thenReturn(List.of(
                profile(1L, "open"),
                profile(2L, "closed"),
                profile(3L, null)));

        List<AppStoreDTO> stores = service.listAppStores();

        assertEquals(List.of("ST-OPEN"), stores.stream().map(AppStoreDTO::getCode).toList());
    }

    private static BizSubject subject(long id, String code) {
        BizSubject subject = new BizSubject();
        subject.setId(id);
        subject.setCode(code);
        subject.setName(code);
        subject.setSubjectType("STORE");
        subject.setStatus("active");
        return subject;
    }

    private static StoreProfile profile(long subjectId, String businessStatus) {
        StoreProfile profile = new StoreProfile();
        profile.setSubjectId(subjectId);
        profile.setActivityCityId(9L);
        profile.setBusinessStatus(businessStatus);
        profile.setCity("长沙市");
        return profile;
    }
}
