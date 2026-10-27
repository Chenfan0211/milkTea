package com.wuling.trade.controller;

import com.wuling.common.api.Result;
import com.wuling.security.AdminUser;
import com.wuling.trade.dto.AfterSaleReviewRequest;
import com.wuling.trade.dto.MallAfterSaleVO;
import com.wuling.trade.service.MallAfterSaleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAfterSaleControllerTest {

    private MallAfterSaleService service;
    private AdminAfterSaleController controller;

    @BeforeEach
    void setUp() {
        service = mock(MallAfterSaleService.class);
        controller = new AdminAfterSaleController(service, mock(com.wuling.common.audit.AuditLogService.class));
        AdminUser.set(9L, "admin");
    }

    @AfterEach
    void tearDown() {
        AdminUser.clear();
    }

    @Test
    void approveUsesJwtOperatorAndOnlyRemark() {
        AfterSaleReviewRequest request = new AfterSaleReviewRequest();
        request.setRemark("同意退货");
        MallAfterSaleVO expected = new MallAfterSaleVO();
        when(service.approve("AS001", "admin", "同意退货")).thenReturn(expected);

        Result<MallAfterSaleVO> result = controller.approve("AS001", request);

        assertEquals(expected, result.getData());
        verify(service).approve("AS001", "admin", "同意退货");
    }

    @Test
    void rejectUsesJwtOperator() {
        AfterSaleReviewRequest request = new AfterSaleReviewRequest();
        request.setRemark("不符合条件");
        MallAfterSaleVO expected = new MallAfterSaleVO();
        when(service.reject("AS001", "admin", "不符合条件")).thenReturn(expected);

        assertEquals(expected, controller.reject("AS001", request).getData());
        verify(service).reject("AS001", "admin", "不符合条件");
    }
}

