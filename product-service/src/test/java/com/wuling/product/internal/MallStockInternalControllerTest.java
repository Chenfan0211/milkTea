package com.wuling.product.internal;

import com.wuling.product.dto.MallSkuInternalView;
import com.wuling.product.dto.MallSkuQueryRequest;
import com.wuling.product.dto.MallStockItemRequest;
import com.wuling.product.dto.MallStockMutationRequest;
import com.wuling.product.dto.MallStockMutationResponse;
import com.wuling.product.service.MallInventoryService;
import com.wuling.product.service.MallProductQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MallStockInternalControllerTest {

    private MallProductQueryService queryService;
    private MallInventoryService inventoryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        queryService = mock(MallProductQueryService.class);
        inventoryService = mock(MallInventoryService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new MallStockInternalController(queryService, inventoryService)).build();
    }

    @Test
    void queryEndpointAcceptsSkuIds() throws Exception {
        MallSkuInternalView view = new MallSkuInternalView();
        view.setSkuId(1L);
        view.setAvailableStock(3);
        when(queryService.queryInternalSkus(List.of(1L))).thenReturn(List.of(view));

        mockMvc.perform(post("/internal/mall/skus/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuIds\":[1]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].skuId").value(1))
                .andExpect(jsonPath("$[0].availableStock").value(3));
    }

    @Test
    void reserveEndpointReturnsSuccessFalseWhenStockIsInsufficient() throws Exception {
        when(inventoryService.reserve(any())).thenReturn(MallStockMutationResponse.fail("可用库存不足"));

        mockMvc.perform(post("/internal/mall/skus/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"O-1\",\"items\":[{\"skuId\":1,\"quantity\":2}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("可用库存不足"));
    }

    @Test
    void allMutationEndpointsExistUnderInternalPath() throws Exception {
        when(inventoryService.confirm(any()))
                .thenReturn(MallStockMutationResponse.ok());
        when(inventoryService.release(any()))
                .thenReturn(MallStockMutationResponse.ok());
        when(inventoryService.restore(any()))
                .thenReturn(MallStockMutationResponse.ok());

        String body = "{\"orderNo\":\"O-2\",\"items\":[{\"skuId\":1,\"quantity\":1}]}";
        mockMvc.perform(post("/internal/mall/skus/confirm")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/internal/mall/skus/release")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/internal/mall/skus/restore")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }
}
