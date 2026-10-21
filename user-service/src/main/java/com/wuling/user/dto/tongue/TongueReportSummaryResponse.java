package com.wuling.user.dto.tongue;

import java.time.LocalDateTime;

/**
 * 舌象报告列表摘要（仅本人最近 6 个月、未删除）。
 *
 * <p>sex/age 是「该次检测时」用户填写的值，供记录列表展示「男/23岁」，
 * 避免用当前本地资料顶替导致历史记录信息不一致。
 */
public record TongueReportSummaryResponse(
        Long id,
        String physique,
        String summarize,
        String reportStatus,
        LocalDateTime createdAt,
        boolean hasImage,
        String sex,
        Integer age
) {
}
