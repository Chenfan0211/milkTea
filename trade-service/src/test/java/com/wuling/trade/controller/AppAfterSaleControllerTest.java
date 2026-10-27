package com.wuling.trade.controller;

import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.security.CurrentUser;
import com.wuling.trade.dto.CreateAfterSaleRequest;
import com.wuling.trade.dto.MallAfterSaleVO;
import com.wuling.trade.service.MallAfterSaleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppAfterSaleControllerTest {

    private MallAfterSaleService service;
    private AppAfterSaleController controller;
    private static final Long USER_ID = 7L;

    @BeforeEach
    void setUp() {
        service = mock(MallAfterSaleService.class);
        controller = new AppAfterSaleController(service);
        CurrentUser.set(USER_ID);
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    @Test
    void createUsesJwtUserAndPathOrderNo() {
        CreateAfterSaleRequest request = new CreateAfterSaleRequest();
        request.setReason("尺码不合适");
        MallAfterSaleVO expected = new MallAfterSaleVO();
        when(service.create(USER_ID, "MO001", request)).thenReturn(expected);

        Result<MallAfterSaleVO> result = controller.create("MO001", request);

        assertEquals(expected, result.getData());
        verify(service).create(USER_ID, "MO001", request);
    }

    @Test
    void detailUsesJwtUser() {
        MallAfterSaleVO expected = new MallAfterSaleVO();
        when(service.detail(USER_ID, "AS001")).thenReturn(expected);

        assertEquals(expected, controller.detail("AS001").getData());
        verify(service).detail(USER_ID, "AS001");
    }
}
