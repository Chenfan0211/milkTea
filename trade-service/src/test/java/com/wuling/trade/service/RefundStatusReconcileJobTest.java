package com.wuling.trade.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wuling.trade.entity.Refund;
import com.wuling.trade.mapper.RefundMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 微信退款「主动查询兜底」扫描任务测试。
 */
class RefundStatusReconcileJobTest {

    private RefundMapper refundMapper;
    private RefundService refundService;
    private RefundStatusReconcileJob job;

    @BeforeAll
    static void initMybatisMetadata() {
        if (TableInfoHelper.getTableInfo(Refund.class) == null) {
            MybatisConfiguration configuration = new MybatisConfiguration();
            MapperBuilderAssistant assistant =
                    new MapperBuilderAssistant(configuration, Refund.class.getName());
            TableInfoHelper.initTableInfo(assistant, Refund.class);
        }
    }

    @BeforeEach
    void setUp() {
        refundMapper = mock(RefundMapper.class);
        refundService = mock(RefundService.class);
        job = new RefundStatusReconcileJob(refundMapper, refundService, true, 100);
    }

    private Refund refund(Long id, LocalDateTime applyTime) {
        Refund r = new Refund();
        r.setId(id);
        r.setRefundNo("RF" + id);
        r.setOrderNo("O" + id);
        r.setStatus(RefundService.STATUS_REFUNDING);
        r.setApplyTime(applyTime);
        return r;
    }

    @Test
    @DisplayName("扫描只处理超过阈值的 REFUNDING 单并推进")
    void sweepsAndAdvancesRefunding() {
        LocalDateTime now = LocalDateTime.now();
        Refund r = refund(1L, now.minusMinutes(10));
        when(refundMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(r));
        when(refundService.syncRefundStatus("O1", "RF1")).thenReturn(true);

        int advanced = job.sweep();

        assertEquals(1, advanced);
        verify(refundService).syncRefundStatus("O1", "RF1");
    }

    @Test
    @DisplayName("未推进的退款单不计数")
    void notAdvancedNotCounted() {
        LocalDateTime now = LocalDateTime.now();
        Refund r = refund(1L, now.minusMinutes(10));
        when(refundMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(r));
        when(refundService.syncRefundStatus("O1", "RF1")).thenReturn(false);

        int advanced = job.sweep();

        assertEquals(0, advanced);
    }

    @Test
    @DisplayName("单条查询抛异常不中断整批")
    void singleFailureDoesNotAbortBatch() {
        LocalDateTime now = LocalDateTime.now();
        Refund r1 = refund(1L, now.minusMinutes(10));
        Refund r2 = refund(2L, now.minusMinutes(10));
        when(refundMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(r1, r2));
        when(refundService.syncRefundStatus("O1", "RF1"))
                .thenThrow(new RuntimeException("boom"));
        when(refundService.syncRefundStatus("O2", "RF2")).thenReturn(true);

        int advanced = job.sweep();

        assertEquals(1, advanced);
        verify(refundService).syncRefundStatus("O2", "RF2");
    }

    @Test
    @DisplayName("关闭时直接返回 0 且不查询")
    void disabledReturnsZeroWithoutQuery() {
        job = new RefundStatusReconcileJob(refundMapper, refundService, false, 100);

        int advanced = job.sweep();

        assertEquals(0, advanced);
        verify(refundMapper, never()).selectList(any(LambdaQueryWrapper.class));
    }

}
