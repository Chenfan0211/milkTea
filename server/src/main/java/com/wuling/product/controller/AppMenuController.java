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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/app")
public class AppMenuController {

    private final ProductQueryService productQueryService;

    public AppMenuController(ProductQueryService productQueryService) {
        this.productQueryService = productQueryService;
    }

    @GetMapping("/menu")
    public Result<List<MenuDTO.MenuTab>> menu() {
        return Result.ok(productQueryService.getMenu());
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
