package com.wuling.product.controller;

import com.wuling.common.api.PageResult;
import com.wuling.common.api.Result;
import com.wuling.product.dto.MallProductAdminDTO;
import com.wuling.product.dto.MallProductSaveRequest;
import com.wuling.product.dto.MallProductStatusRequest;
import com.wuling.product.dto.MallSkuAdminDTO;
import com.wuling.product.dto.MallStockAdjustRequest;
import com.wuling.product.service.MallProductAdminService;
import com.wuling.security.AdminUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端商城商品与 SKU 维护。
 */
@RestController
@RequestMapping("/api/v1/admin/mall/products")
public class AdminMallProductController {

    private final MallProductAdminService productAdminService;

    public AdminMallProductController(MallProductAdminService productAdminService) {
        this.productAdminService = productAdminService;
    }

    @GetMapping
    public Result<PageResult<MallProductAdminDTO>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return Result.ok(productAdminService.pageProducts(page, size, categoryId, status, keyword));
    }

    @GetMapping("/{id}")
    public Result<MallProductAdminDTO> detail(@PathVariable Long id) {
        return Result.ok(productAdminService.getProduct(id));
    }

    @PostMapping
    public Result<MallProductAdminDTO> create(@Valid @RequestBody MallProductSaveRequest request) {
        return Result.ok(productAdminService.create(request));
    }

    @PutMapping("/{id}")
    public Result<MallProductAdminDTO> update(@PathVariable Long id,
                                              @Valid @RequestBody MallProductSaveRequest request) {
        return Result.ok(productAdminService.update(id, request));
    }

    @PatchMapping("/{id}/status")
    public Result<MallProductAdminDTO> updateStatus(@PathVariable Long id,
                                                    @Valid @RequestBody MallProductStatusRequest request) {
        return Result.ok(productAdminService.updateStatus(id, request.getStatus()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        productAdminService.delete(id);
        return Result.ok();
    }

    @PutMapping("/{id}/skus")
    public Result<List<MallSkuAdminDTO>> saveSkus(@PathVariable Long id,
                                                  @RequestBody List<MallSkuAdminDTO> skus) {
        return Result.ok(productAdminService.saveSkus(id, skus));
    }

    @PatchMapping("/{id}/skus/{skuId}/stock")
    public Result<MallSkuAdminDTO> adjustStock(@PathVariable Long id,
                                               @PathVariable Long skuId,
                                               @RequestBody MallStockAdjustRequest request) {
        return Result.ok(productAdminService.adjustStock(
                id, skuId, request, String.valueOf(AdminUser.require())));
    }
}
