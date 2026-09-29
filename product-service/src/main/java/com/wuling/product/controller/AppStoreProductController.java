package com.wuling.product.controller;

import com.wuling.common.api.PageResult;
import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.product.dto.StoreProductDTO;
import com.wuling.product.dto.StoreProductPageDTO;
import com.wuling.product.dto.StoreProductDetailDTO;
import com.wuling.product.port.StoreOperatorPort;
import com.wuling.product.service.StoreProductService;
import com.wuling.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 小程序端：门店选品管理。
 *
 * <p>与管理端（{@code /api/v1/admin/product/**}）的区别：本接口供小程序门店
 * 经营角色自助选品，鉴权基于小程序 JWT，并对「调用者是否经营该门店」做
 * 服务端强校验（{@link StoreOperatorPort}，fail-closed）。
 *
 * <p><b>越权防护</b>：{@code storeSubjectId} 由前端传入，但服务端会用当前登录
 * 用户（取自 JWT）核验其确实经营该门店；否则任何登录用户都能改他人门店选品。
 *
 * <p><b>数据口径</b>：基础数据为平台已上架商品；{@code listed} 表示该门店是否
 * 已上架（product_store 有无记录）。门店下架后消费端点单页实时生效。
 */
@RestController
@RequestMapping("/api/v1/app/workbench/store")
public class AppStoreProductController {

    private final StoreProductService storeProductService;
    private final StoreOperatorPort storeOperatorPort;

    public AppStoreProductController(StoreProductService storeProductService,
                                     StoreOperatorPort storeOperatorPort) {
        this.storeProductService = storeProductService;
        this.storeOperatorPort = storeOperatorPort;
    }

    /**
     * 门店选品分页列表。
     *
     * @param storeSubjectId 门店主体 ID（须为当前用户经营的门店）
     * @param current        页码，从 1 开始
     * @param size           每页条数（上限 100）
     * @param keyword        关键词（匹配商品名称或编号）
     * @param categoryId     分类筛选
     * @param listed         true=仅已上架 / false=仅已下架 / 不传=全部
     */
    @GetMapping("/{storeSubjectId}/products")
    public Result<StoreProductPageDTO> products(
            @PathVariable Long storeSubjectId,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Boolean listed) {

        requireStoreOperator(storeSubjectId);
        return Result.ok(storeProductService.pageStoreProducts(
                storeSubjectId, current, size, keyword, categoryId, listed));
    }

    /**
     * 门店选品详情（只读）。
     *
     * <p>只读接口：不提供任何写操作，上下架统一走列表页的单条 / 批量接口。
     * 归属校验与列表一致（{@link #requireStoreOperator}，fail-closed）。
     *
     * @param storeSubjectId 门店主体 ID（须为当前用户经营的门店）
     * @param productId      商品业务编号
     */
    @GetMapping("/{storeSubjectId}/products/{productId}")
    public Result<StoreProductDetailDTO> productDetail(@PathVariable Long storeSubjectId,
                                                       @PathVariable String productId) {
        requireStoreOperator(storeSubjectId);
        StoreProductDetailDTO detail = storeProductService.getStoreProductDetail(storeSubjectId, productId);
        if (detail == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在或平台已下架");
        }
        return Result.ok(detail);
    }

    /** 单条上架 / 下架。 */
    @PutMapping("/{storeSubjectId}/products/{productId}/listing")
    public Result<Void> updateListing(@PathVariable Long storeSubjectId,
                                      @PathVariable Long productId,
                                      @RequestBody Map<String, Object> body) {
        requireStoreOperator(storeSubjectId);
        Object raw = body == null ? null : body.get("listed");
        if (!(raw instanceof Boolean)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "缺少上架状态参数");
        }
        storeProductService.updateListing(storeSubjectId, productId, (Boolean) raw);
        return Result.ok();
    }

    /** 批量上架 / 下架。 */
    @PutMapping("/{storeSubjectId}/products/listing")
    public Result<Map<String, Object>> updateListingBatch(@PathVariable Long storeSubjectId,
                                                          @RequestBody Map<String, Object> body) {
        requireStoreOperator(storeSubjectId);
        Object rawListed = body == null ? null : body.get("listed");
        if (!(rawListed instanceof Boolean)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "缺少上架状态参数");
        }
        Object rawIds = body.get("productIds");
        if (!(rawIds instanceof List<?> rawProductIds) || rawProductIds.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请先选择商品");
        }
        List<Long> productIds = rawProductIds.stream()
                .map(this::asLong)
                .filter(java.util.Objects::nonNull)
                .toList();
        int affected = storeProductService.updateListingBatch(storeSubjectId, productIds, (Boolean) rawListed);
        return Result.ok(Map.of("affected", affected));
    }

    /** 越权防护：必须确认当前用户经营该门店，否则拒绝（fail-closed）。 */
    private void requireStoreOperator(Long storeSubjectId) {
        Long userId = CurrentUser.require();
        if (!storeOperatorPort.isStoreOperator(userId, storeSubjectId)) {
            throw new BusinessException(ResultCode.FORBIDDEN,
                    "无权管理该门店选品，请确认已开通门店经营角色");
        }
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
