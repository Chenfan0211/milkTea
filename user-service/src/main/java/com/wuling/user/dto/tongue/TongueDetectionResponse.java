package com.wuling.user.dto.tongue;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 舌象检测结果视图。
 *
 * <p>不暴露第三方 reportId、file-service storedName 或任何公开图片 URL。
 */
public record TongueDetectionResponse(
        Long id,
        String physique,
        String summarize,
        String reportStatus,
        LocalDateTime createdAt,
        List<TongueProductItem> productList,
        List<TongueReportGroup> reportGroups,
        boolean hasImage
) {
}
