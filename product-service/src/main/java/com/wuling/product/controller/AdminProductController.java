package com.wuling.product.controller;

import com.wuling.common.api.PageResult;
import com.wuling.common.api.Result;
import com.wuling.product.dto.AdminProductDTO;
import com.wuling.product.dto.CategoryDTO;
import com.wuling.product.service.ProductQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/product")
public class AdminProductController {

    private final ProductQueryService productQueryService;

    public AdminProductController(ProductQueryService productQueryService) {
        this.productQueryService = productQueryService;
    }

    @GetMapping("/categories")
    public Result<List<CategoryDTO>> categories() {
        return Result.ok(productQueryService.listCategories());
    }

    @GetMapping("/list")
    public Result<PageResult<AdminProductDTO>> list(@RequestParam(defaultValue = "1") long current,
                                                    @RequestParam(defaultValue = "10") long size,
                                                    @RequestParam(required = false) String search) {
        return Result.ok(productQueryService.pageAdminProducts(current, size, search));
    }
}
