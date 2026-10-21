package com.wuling.user.dto.tongue;

import java.util.List;

/**
 * 舌象报告分组内的章节。
 *
 * <p><b>parentId</b>：父节点 id（顶层为 null）。第三方报告是树形结构，
 * 小程序需要按「周X / 节气 / 功法」这类父节点做 Tab 切换，因此保留父指针，
 * 由前端按需要还原层级，避免下发重复的嵌套结构。
 *
 * <p><b>block</b>：章节的内容形态，取值见 {@link #BLOCK_TEXT} 等常量；
 * 前端据此选择渲染器，未识别取值一律走通用兜底渲染。
 */
public record TongueReportSection(
        String id,
        int level,
        String parentId,
        String title,
        String block,
        String content,
        List<TongueReportDescription> descriptions,
        TongueReportMedia media,
        TongueAnalysis analysis
) {

    /** 仅富文本正文。 */
    public static final String BLOCK_TEXT = "TEXT";
    /** 仅 descriptions（标题 + 内容成对块，如 健康特点 / 组成 / 制法 / 服法 / 功效）。 */
    public static final String BLOCK_DESCRIPTIONS = "DESCRIPTIONS";
    /** 舌象分析指标（factor / feature）。 */
    public static final String BLOCK_ANALYSIS = "ANALYSIS";
    /** 仅音频媒体（调体音乐）。 */
    public static final String BLOCK_MEDIA_AUDIO = "MEDIA_AUDIO";
    /** 仅视频媒体。 */
    public static final String BLOCK_MEDIA_VIDEO = "MEDIA_VIDEO";
    /** 仅图片媒体。 */
    public static final String BLOCK_MEDIA_IMAGE = "MEDIA_IMAGE";
    /** 媒体 + descriptions（穴位操作：视频 + 定位/手法/功效）。 */
    public static final String BLOCK_MEDIA_DETAIL = "MEDIA_DETAIL";
    /** 仅作为父节点，内容由子章节承载。 */
    public static final String BLOCK_TITLE_ONLY = "TITLE_ONLY";
}