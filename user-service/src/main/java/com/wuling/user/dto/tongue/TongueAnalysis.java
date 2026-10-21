package com.wuling.user.dto.tongue;

import java.util.List;

/**
 * 舌象分析指标。
 */
public record TongueAnalysis(List<Factor> factors, List<Feature> features) {

    public record Factor(String tongueType, String tongueVal, String highlight) {
    }

    public record Feature(String tongueType, String tongueVal, String difference) {
    }
}
