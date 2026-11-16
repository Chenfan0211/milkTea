package com.wuling.finance;

import com.wuling.common.alert.AlertChannel;
import com.wuling.finance.mapper.ReconcileIssueMapper;
import com.wuling.finance.mapper.SettlementRecordMapper;
import com.wuling.finance.mapper.SplitSnapshotMapper;
import com.wuling.finance.service.ReconcileService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReconcileServiceTest {

    @Test
    void splitAmountCheckIncludesLeaderAmount() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        ReconcileService service = new ReconcileService(
                jdbcTemplate,
                mock(SplitSnapshotMapper.class),
                mock(SettlementRecordMapper.class),
                mock(ReconcileIssueMapper.class),
                mock(AlertChannel.class));

        service.reconcile();

        ArgumentCaptor<String> sqls = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).queryForList(sqls.capture());
        assertTrue(sqls.getAllValues().stream()
                        .anyMatch(sql -> sql.contains("s.leader_amount")),
                "商城三方守恒必须把团长金额计入对账合计");
    }
}
