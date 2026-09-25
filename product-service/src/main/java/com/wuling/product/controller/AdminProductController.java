package com.wuling.product.controller;

import com.wuling.common.api.PageResult;
import com.wuling.common.api.Result;
import com.wuling.product.dto.AdminProductDTO;
import com.wuling.product.dto.CategoryDTO;
import com.wuling.product.dto.SpecGroupsDTO;
import com.wuling.product.service.AdminProductWriteService;
import com.wuling.product.service.ProductQueryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理端商品维护接口。
 *
 * <p>鉴权：由 {@link com.wuling.product.config.ProductSecurityConfig} 注册的
 * {@code AdminAuthInterceptor} 校验管理端 token（type = access），
 * 网关侧 {@code GatewayAuthPolicy} 亦判定 {@code /api/v1/admin/**} 需登录。
 *
 * <p>写操作说明：新增 / 编辑 / 上下架 / 删除均落库到 product 表，
 * 删除为逻辑删除（deleted = 1），与全局约定一致。
 */
@RestController
@RequestMapping("/api/v1/admin/product")
public class AdminProductController {

    private final ProductQueryService productQueryService;
    private final AdminProductWriteService adminProductWriteService;

    public AdminProductController(ProductQueryService productQueryService,
                                  AdminProductWriteService adminProductWriteService) {
        this.productQueryService = productQueryService;
        this.adminProductWriteService = adminProductWriteService;
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

    /** 新增商品 */
    @PostMapping("/create")
    public Result<AdminProductDTO> create(@RequestBody Map<String, Object> payload) {
        return Result.ok(adminProductWriteService.create(payload));
    }

    /** 编辑商品 */
    @PutMapping("/{id}")
    public Result<AdminProductDTO> update(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        return Result.ok(adminProductWriteService.update(id, payload));
    }

    /** 上下架：onSale = on / off */
    @PatchMapping("/{id}/on-sale")
    public Result<AdminProductDTO> updateOnSale(@PathVariable Long id,
                                                @RequestParam String onSale) {
        return Result.ok(adminProductWriteService.updateOnSale(id, onSale));
    }

    // ---------- 规格组 ----------

    /** 读取商品的规格组（组 -> 选项） */
    @GetMapping("/{id}/spec-groups")
    public Result<SpecGroupsDTO> getSpecGroups(@PathVariable Long id) {
        return Result.ok(adminProductWriteService.getSpecGroups(id));
    }

    /** 保存商品的规格组（整体替换 product_spec） */
    @PutMapping("/{id}/spec-groups")
    public Result<SpecGroupsDTO> saveSpecGroups(@PathVariable Long id,
                                                @RequestBody SpecGroupsDTO payload) {
        return Result.ok(adminProductWriteService.saveSpecGroups(id, payload));
    }

    // ---------- 门店关联 ----------

    /** 读取商品已关联的门店主体 ID */
    @GetMapping("/{id}/stores")
    public Result<List<Long>> getStores(@PathVariable Long id) {
        return Result.ok(adminProductWriteService.getStoreIds(id));
    }

    /** 保存商品的门店关联（整体替换 product_store） */
    @PutMapping("/{id}/stores")
    public Result<List<Long>> saveStores(@PathVariable Long id, @RequestBody List<Long> storeSubjectIds) {
        return Result.ok(adminProductWriteService.saveStoreIds(id, storeSubjectIds));
    }

    /** 逻辑删除 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        adminProductWriteService.delete(id);
        return Result.ok();
    }
}
