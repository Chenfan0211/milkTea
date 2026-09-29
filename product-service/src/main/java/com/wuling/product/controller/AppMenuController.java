package com.wuling.product.controller;

import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.product.dto.MenuDTO;
import com.wuling.product.dto.ProductDetailDTO;
import com.wuling.product.service.ProductQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/app")
public class AppMenuController {

    private final ProductQueryService productQueryService;

    public AppMenuController(ProductQueryService productQueryService) {
        this.productQueryService = productQueryService;
    }

    /**
     * 菜单（tab -> group -> category -> products）。
     *
     * <p><b>storeSubjectId</b>（2026-09 新增）：门店选品下架的商品不应出现在该门店
     * 点单页。传入门店主体 ID 时，服务端按 product_store 过滤掉「本店未上架」的商品；
     * 不传时返回平台全量菜单（兼容旧调用 / 未选门店场景）。
     */
    @GetMapping("/menu")
    public Result<List<MenuDTO.MenuTab>> menu(
            @RequestParam(required = false) Long storeSubjectId) {
        return Result.ok(productQueryService.getMenu(storeSubjectId));
    }

    @GetMapping("/products/{productId}")
    public Result<ProductDetailDTO> productDetail(@PathVariable String productId) {
        ProductDetailDTO detail = productQueryService.getProductDetail(productId);
        if (detail == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在");
        }
        return Result.ok(detail);
    }
}
