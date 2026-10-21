package com.wuling.user.dto.tongue;

import java.util.List;

/**
 * 舌象报告商品推荐项。
 *
 * <p>前四个字段来自第三方报告（goodsId/goodsName/reason/media）；
 * 其余字段是「报告商品名 → 商城商品」的匹配结果：
 * 命中时 matched=true 并带上商城商品的主图、价格、标签与适宜体质，
 * 未命中时 matched=false，小程序按《舌诊还原方案》不渲染该商品卡。
 */
public record TongueProductItem(
        String goodsId,
        String goodsName,
        String reason,
        TongueReportMedia media,
        String productId,
        String image,
        Long price,
        Long originalPrice,
        List<String> tags,
        List<String> suitableConstitutions,
        boolean matched
) {
}