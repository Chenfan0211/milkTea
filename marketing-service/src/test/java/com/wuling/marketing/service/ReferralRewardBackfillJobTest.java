package com.wuling.marketing.service;

import com.wuling.marketing.mapper.ReferralRecordMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

/** 邀请首单奖励补发任务：候选扫描、逐条补发与容错。 */
class ReferralRewardBackfillJobTest {

    private final ReferralRecordMapper referralRecordMapper = mock(ReferralRecordMapper.class);
    private final ReferralRewardService referralRewardService = mock(ReferralRewardService.class);
    private final ReferralRewardBackfillJob job =
            new ReferralRewardBackfillJob(referralRecordMapper, referralRewardService, true);

    private Map<String, Object> candidate(long inviteeUserId, String orderNo) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("inviteeUserId", inviteeUserId);
        row.put("orderNo", orderNo);
        return row;
    }

    @Test
    @DisplayName("无候选时不做任何发奖")
    void noCandidatesDoesNothing() {
        when(referralRecordMapper.selectMissingRewardCandidates(anyInt())).thenReturn(List.of());

        assertEquals(0, job.sweep());
        verify(referralRewardService, never()).rewardFirstOrder(any(), any());
    }

    @Test
    @DisplayName("有候选时逐条补发，并统计实际发奖成功条数")
    void rewardsCandidatesAndCountsSuccesses() {
        List<Map<String, Object>> candidates = new ArrayList<>();
        candidates.add(candidate(20L, "ORDER-1"));
        candidates.add(candidate(21L, "ORDER-2"));
        candidates.add(candidate(22L, "ORDER-3"));
        when(referralRecordMapper.selectMissingRewardCandidates(anyInt())).thenReturn(candidates);
        // 第 2 条返回 false（如已发过 / 邀请人已失效），不计入成功数。
        when(referralRewardService.rewardFirstOrder(20L, "ORDER-1")).thenReturn(true);
        when(referralRewardService.rewardFirstOrder(21L, "ORDER-2")).thenReturn(false);
        when(referralRewardService.rewardFirstOrder(22L, "ORDER-3")).thenReturn(true);

        assertEquals(2, job.sweep());
        verify(referralRewardService).rewardFirstOrder(20L, "ORDER-1");
        verify(referralRewardService).rewardFirstOrder(21L, "ORDER-2");
        verify(referralRewardService).rewardFirstOrder(22L, "ORDER-3");
    }

    @Test
    @DisplayName("单条补发异常不阻断其余候选")
    void singleFailureDoesNotBlockOthers() {
        List<Map<String, Object>> candidates = new ArrayList<>();
        candidates.add(candidate(20L, "ORDER-1"));
        candidates.add(candidate(21L, "ORDER-2"));
        when(referralRecordMapper.selectMissingRewardCandidates(anyInt())).thenReturn(candidates);
        when(referralRewardService.rewardFirstOrder(20L, "ORDER-1"))
                .thenThrow(new IllegalStateException("db down"));
        when(referralRewardService.rewardFirstOrder(21L, "ORDER-2")).thenReturn(true);

        assertEquals(1, job.sweep());
        verify(referralRewardService).rewardFirstOrder(21L, "ORDER-2");
    }

    @Test
    @DisplayName("扫描异常被吞掉，返回 0，不向上抛出")
    void scanFailureIsSwallowed() {
        when(referralRecordMapper.selectMissingRewardCandidates(anyInt()))
                .thenThrow(new RuntimeException("db down"));

        assertEquals(0, job.sweep());
    }

    @Test
    @DisplayName("非法候选行（缺 orderNo / 非数字 userId）被跳过")
    void skipsInvalidRows() {
        List<Map<String, Object>> candidates = new ArrayList<>();
        candidates.add(candidate(20L, null));
        candidates.add(candidate(21L, "  "));
        Map<String, Object> badId = new LinkedHashMap<>();
        badId.put("inviteeUserId", "abc");
        badId.put("orderNo", "ORDER-3");
        candidates.add(badId);
        when(referralRecordMapper.selectMissingRewardCandidates(anyInt())).thenReturn(candidates);

        assertEquals(0, job.sweep());
        verify(referralRewardService, never()).rewardFirstOrder(any(), any());
    }

    @Test
    @DisplayName("任务关闭时不扫描、不补发")
    void disabledJobDoesNothing() {
        ReferralRewardBackfillJob disabled =
                new ReferralRewardBackfillJob(referralRecordMapper, referralRewardService, false);

        assertEquals(0, disabled.sweep());
        verify(referralRecordMapper, never()).selectMissingRewardCandidates(anyInt());
        verify(referralRewardService, never()).rewardFirstOrder(any(), any());
    }
}
