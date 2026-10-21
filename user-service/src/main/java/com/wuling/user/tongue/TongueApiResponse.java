package com.wuling.user.tongue;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 舌象第三方接口响应。
 *
 * <p>{@code data} 保留完整 data 节点，便于报告原始 JSON 留档和后续字段扩展。
 */
public record TongueApiResponse(
        boolean success,
        String status,
        String message,
        String code,
        JsonNode data
) {
}
