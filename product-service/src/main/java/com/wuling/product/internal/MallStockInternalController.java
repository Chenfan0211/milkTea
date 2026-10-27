package com.wuling.product.internal;

import com.wuling.product.dto.MallSkuInternalView;
import com.wuling.product.dto.MallSkuQueryRequest;
import com.wuling.product.dto.MallStockMutationRequest;
import com.wuling.product.dto.MallStockMutationResponse;
import com.wuling.product.service.MallInventoryService;
import com.wuling.product.service.MallProductQueryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * trade-service 调用的商城 SKU 库存契约。
 *
 * <p>仅经内网服务发现访问，不配置网关路由。查询与所有库存变更均为 POST，
 * 变更接口的幂等键为 orderNo + change_type。
 */
@RestController
@RequestMapping("/internal/mall/skus")
public class MallStockInternalController {

    private final MallProductQueryService productQueryService;
    private final MallInventoryService inventoryService;

    public MallStockInternalController(MallProductQueryService productQueryService,
                                       MallInventoryService inventoryService) {
        this.productQueryService = productQueryService;
        this.inventoryService = inventoryService;
    }

    @PostMapping("/query")
    public List<MallSkuInternalView> query(@Valid @RequestBody MallSkuQueryRequest request) {
        return productQueryService.queryInternalSkus(request.getSkuIds());
    }

    @PostMapping("/reserve")
    public MallStockMutationResponse reserve(@Valid @RequestBody MallStockMutationRequest request) {
        return inventoryService.reserve(request);
    }

    @PostMapping("/confirm")
    public MallStockMutationResponse confirm(@Valid @RequestBody MallStockMutationRequest request) {
        return inventoryService.confirm(request);
    }

    @PostMapping("/release")
    public MallStockMutationResponse release(@Valid @RequestBody MallStockMutationRequest request) {
        return inventoryService.release(request);
    }

    @PostMapping("/restore")
    public MallStockMutationResponse restore(@Valid @RequestBody MallStockMutationRequest request) {
        return inventoryService.restore(request);
    }
}
