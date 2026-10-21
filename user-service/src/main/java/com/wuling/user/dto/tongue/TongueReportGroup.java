package com.wuling.user.dto.tongue;

import java.util.List;

/**
 * 舌象报告一级分类。
 *
 * <p>tabKey 是把第三方一级标题映射成小程序「详情」页五个 Tab 的稳定键：
 * overview（概况）/diet（饮食）/acupoint（穴位）/emotion（情志）/sport（运动），
 * 未命中的标题返回 other，小程序会把它追加成额外 Tab，保证内容不丢。
 */
public record TongueReportGroup(
        String id,
        String title,
        String tabKey,
        List<TongueProductItem> products,
        List<TongueReportSection> sections
) {
}