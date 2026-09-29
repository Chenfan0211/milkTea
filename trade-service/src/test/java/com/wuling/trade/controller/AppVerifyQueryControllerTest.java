package com.wuling.trade.controller;

import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.security.CurrentUser;
import com.wuling.trade.dto.VerifyRequest;
import com.wuling.trade.port.StoreOperatorPort;
import com.wuling.trade.service.VerifyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 小程序端兑换核销接口：门店归属校验 + 强制 EXCHANGE 类型 + 操作人取 JWT。 */
class AppVerifyQueryControllerTest {

    private static final Long USER_ID = 7L;
    private static final Long STORE_SUBJECT_ID = 101L;

    private VerifyService verifyService;
    private StoreOperatorPort storeOperatorPort;
    private AppVerifyQueryController controller;

    @BeforeEach
    void setUp() {
        verifyService = mock(VerifyService.class);
        storeOperatorPort = mock(StoreOperatorPort.class);
        controller = new AppVerifyQueryController(verifyService, storeOperatorPort);
        CurrentUser.set(USER_ID);
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    @Test
    @DisplayName("兑换核销：门店经营角色通过后，强制 EXCHANGE 类型并取 JWT 操作人")
    void verifyExchangeForcesExchangeTypeAndJwtOperator() {
        when(storeOperatorPort.isStoreOperator(USER_ID, STORE_SUBJECT_ID)).thenReturn(true);
        VerifyService.VerifyResult expected = new VerifyService.VerifyResult();
        expected.setSuccess(true);
        when(verifyService.verify(any(VerifyRequest.class))).thenReturn(expected);

        VerifyRequest request = new VerifyRequest();
        request.setCode("CZ123");

        Result<VerifyService.VerifyResult> result = controller.verifyExchange(STORE_SUBJECT_ID, request);

        assertSame(expected, result.getData());
        verify(storeOperatorPort).isStoreOperator(USER_ID, STORE_SUBJECT_ID);
        // 强制兑换类型 + 操作人取 JWT（不接受前端传入）
        verify(verifyService).verify(any(VerifyRequest.class));
        assertEquals("EXCHANGE", request.getType(), "必须强制 EXCHANGE 类型");
        assertEquals("mini-user-" + USER_ID, request.getOperator(), "操作人必须取 JWT");
    }

    @Test
    @DisplayName("兑换核销：非门店经营角色一律 FORBIDDEN，不触达服务层")
    void verifyExchangeRejectsNonOperator() {
        when(storeOperatorPort.isStoreOperator(USER_ID, STORE_SUBJECT_ID)).thenReturn(false);

        VerifyRequest request = new VerifyRequest();
        request.setCode("CZ123");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.verifyExchange(STORE_SUBJECT_ID, request));
        assertEquals(ResultCode.FORBIDDEN, ex.getCode());
        verify(verifyService, org.mockito.Mockito.never()).verify(any(VerifyRequest.class));
    }

    @Test
    @DisplayName("兑换核销：门店归属查询异常按拒绝处理（fail-closed）")
    void verifyExchangeFailsClosedOnOwnershipError() {
        when(storeOperatorPort.isStoreOperator(USER_ID, STORE_SUBJECT_ID)).thenThrow(new RuntimeException("db down"));

        VerifyRequest request = new VerifyRequest();
        request.setCode("CZ123");

        // fail-closed：isStoreOperator 抛异常向上传播，不触达核销
        assertThrows(RuntimeException.class,
                () -> controller.verifyExchange(STORE_SUBJECT_ID, request));
        verify(verifyService, org.mockito.Mockito.never()).verify(any(VerifyRequest.class));
    }
}
