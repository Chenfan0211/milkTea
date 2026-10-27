package com.wuling.product.controller;

import com.wuling.common.api.Result;
import com.wuling.product.dto.MallCategoryDTO;
import com.wuling.product.dto.MallCategorySaveRequest;
import com.wuling.product.service.MallCategoryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端商城分类。鉴权由 ProductSecurityConfig 注册的 AdminAuthInterceptor 完成。
 */
@RestController
@RequestMapping("/api/v1/admin/mall/categories")
public class AdminMallCategoryController {

    private final MallCategoryService categoryService;

    public AdminMallCategoryController(MallCategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public Result<List<MallCategoryDTO>> list() {
        return Result.ok(categoryService.listAdminCategories());
    }

    @GetMapping("/{id}")
    public Result<MallCategoryDTO> detail(@PathVariable Long id) {
        return Result.ok(categoryService.getAdminCategory(id));
    }

    @PostMapping
    public Result<MallCategoryDTO> create(@Valid @RequestBody MallCategorySaveRequest request) {
        return Result.ok(categoryService.create(request));
    }

    @PutMapping("/{id}")
    public Result<MallCategoryDTO> update(@PathVariable Long id,
                                          @Valid @RequestBody MallCategorySaveRequest request) {
        return Result.ok(categoryService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return Result.ok();
    }
}
