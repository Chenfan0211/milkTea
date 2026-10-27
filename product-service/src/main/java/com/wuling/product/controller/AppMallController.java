package com.wuling.product.controller;

import com.wuling.common.api.PageResult;
import com.wuling.common.api.Result;
import com.wuling.product.dto.MallCategoryDTO;
import com.wuling.product.dto.MallProductCardDTO;
import com.wuling.product.dto.MallProductDetailDTO;
import com.wuling.product.dto.MallSkuValidateRequest;
import com.wuling.product.dto.MallSkuValidateResponse;
import com.wuling.product.service.MallCategoryService;
import com.wuling.product.service.MallProductQueryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 小程序商城公开接口。商品数据无需登录，由网关公开策略放行。
 */
@RestController
@RequestMapping("/api/v1/app/mall")
public class AppMallController {

    private final MallCategoryService categoryService;
    private final MallProductQueryService productQueryService;

    public AppMallController(MallCategoryService categoryService,
                             MallProductQueryService productQueryService) {
        this.categoryService = categoryService;
        this.productQueryService = productQueryService;
    }

    @GetMapping("/categories")
    public Result<List<MallCategoryDTO>> categories() {
        return Result.ok(categoryService.listAppCategories());
    }

    @GetMapping("/products")
    public Result<PageResult<MallProductCardDTO>> products(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return Result.ok(productQueryService.pageAppProducts(categoryId, page, size));
    }

    @GetMapping("/products/{id}")
    public Result<MallProductDetailDTO> product(@PathVariable Long id) {
        return Result.ok(productQueryService.getAppProduct(id));
    }

    @PostMapping("/skus/validate")
    public Result<MallSkuValidateResponse> validate(@Valid @RequestBody MallSkuValidateRequest request) {
        return Result.ok(productQueryService.validateSkus(request));
    }
}
