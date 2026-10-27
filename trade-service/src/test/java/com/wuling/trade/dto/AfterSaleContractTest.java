package com.wuling.trade.dto;

import com.wuling.trade.entity.MallAfterSale;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AfterSaleContractTest {

    @Test
    void viewAndEntityUseExpectedPackages() throws Exception {
        assertEquals("com.wuling.trade.dto.MallAfterSaleVO",
                Class.forName("com.wuling.trade.dto.MallAfterSaleVO").getName());
        assertEquals("com.wuling.trade.entity.MallAfterSale",
                MallAfterSale.class.getName());
    }

    @Test
    void requestContractsExposeOnlyServerResolvedInputs() throws Exception {
        assertNotNull(CreateAfterSaleRequest.class.getDeclaredField("reason"));
        assertNotNull(CreateAfterSaleRequest.class.getDeclaredField("items"));
        assertNotNull(CreateAfterSaleRequest.Item.class.getDeclaredField("orderItemId"));
        assertNotNull(CreateAfterSaleRequest.Item.class.getDeclaredField("quantity"));
        assertNotNull(AfterSaleReviewRequest.class.getDeclaredField("remark"));

        for (var field : CreateAfterSaleRequest.class.getDeclaredFields()) {
            assertTrue(!field.getName().toLowerCase().contains("amount"),
                    "客户端请求不得携带退款金额");
        }
    }
}
