package com.wuling.user.dto.tongue;

/**
 * 第三方报告媒体。
 *
 * <p>kind 以 URL 后缀重新推断，因为第三方会把 MP3 统一标成 video。
 * duration 来自第三方节点的 duration/time/length 字段，缺失时为 null，
 * 小程序仅在有时长时才渲染角标。
 */
public record TongueReportMedia(String kind, String thumbnail, String source, String duration) {
}