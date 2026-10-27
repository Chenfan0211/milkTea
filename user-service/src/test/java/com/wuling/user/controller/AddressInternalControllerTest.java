package com.wuling.user.controller;

import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.security.CurrentUser;
import com.wuling.user.dto.AddressSnapshotResponse;
import com.wuling.user.service.AddressService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AddressInternalControllerTest {

    private AddressService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(AddressService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AddressInternalController(service)).build();
        CurrentUser.clear();
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    @Test
    void returnsRawAddressSnapshotByExplicitUserIdWithoutMiniAppAuthentication() throws Exception {
        when(service.snapshot(7L, 12L)).thenReturn(new AddressSnapshotResponse(
                "李小茶", "13800138000", "上海市", "上海市", "浦东新区", "世纪大道100号"
        ));

        mockMvc.perform(get("/internal/addresses/12").param("userId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.recipientName").value("李小茶"))
                .andExpect(jsonPath("$.phone").value("13800138000"))
                .andExpect(jsonPath("$.detailAddress").value("世纪大道100号"));

        verify(service).snapshot(7L, 12L);
    }

    @Test
    void returnsFoundFalseWhenAddressIsNotOwnedByUser() throws Exception {
        when(service.snapshot(7L, 99L)).thenThrow(
                new BusinessException(ResultCode.NOT_FOUND, "收货地址不存在"));

        mockMvc.perform(get("/internal/addresses/99").param("userId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false));
    }
}