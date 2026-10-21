package com.wuling.user.dto.tongue;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 舌象报告详情视图。
 *
 * <p>不暴露第三方 reportId、file-service storedName 或任何公开图片 URL。
 * sex/age 为「该次检测时」填写的值，详情页头部据此展示「男/23岁 · 体质」。
 */
public record TongueReportDetailResponse(
        Long id,
        String physique,
        String summarize,
        String reportStatus,
        LocalDateTime createdAt,
        List<TongueProductItem> productList,
        List<TongueReportGroup> reportGroups,
        boolean hasImage,
        String sex,
        Integer age
) {
}
