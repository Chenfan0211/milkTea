package com.wuling.user.tongue;

import java.util.List;

/**
 * 商城商品卡（报告推荐商品按名称匹配后的结果）。
 *
 * <p>只承载小程序商品卡需要的只读字段：主图、价格与标签；
 * 不含成本价、佣金等内部经营字段。
 */
public record ProductCard(
        String productId,
        String name,
        String image,
        Long price,
        Long originalPrice,
        List<String> tags,
        List<String> suitableConstitutions
) {
}