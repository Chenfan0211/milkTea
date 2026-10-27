package com.wuling.user.controller;

import com.wuling.common.exception.GlobalExceptionHandler;
import com.wuling.security.CurrentUser;
import com.wuling.user.dto.AddressResponse;
import com.wuling.user.service.AddressService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AddressControllerTest {

    private AddressService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(AddressService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AddressController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        CurrentUser.set(7L);
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    @Test
    void listUsesCurrentUserFromJwt() throws Exception {
        when(service.list(7L)).thenReturn(List.of(response(11L, true)));

        mockMvc.perform(get("/api/v1/app/addresses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].id").value(11))
                .andExpect(jsonPath("$.data[0].recipientName").value("李小茶"));

        verify(service).list(7L);
    }

    @Test
    void createUsesCurrentUserAndReturnsSavedAddress() throws Exception {
        when(service.create(eq(7L), any())).thenReturn(response(11L, true));

        mockMvc.perform(post("/api/v1/app/addresses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isDefault").value(true));

        verify(service).create(eq(7L), any());
    }

    @Test
    void updateUsesCurrentUserAndAddressId() throws Exception {
        when(service.update(eq(7L), eq(11L), any())).thenReturn(response(11L, true));

        mockMvc.perform(put("/api/v1/app/addresses/11")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(11));

        verify(service).update(eq(7L), eq(11L), any());
    }

    @Test
    void deleteUsesCurrentUserAndAddressId() throws Exception {
        mockMvc.perform(delete("/api/v1/app/addresses/11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(service).delete(7L, 11L);
    }

    @Test
    void setDefaultUsesCurrentUserAndAddressId() throws Exception {
        mockMvc.perform(put("/api/v1/app/addresses/11/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(service).setDefault(7L, 11L);
    }

    private static String validJson(boolean isDefault) {
        return """
                {
                  "recipientName": "李小茶",
                  "phone": "13800138000",
                  "provinceCode": "310000",
                  "provinceName": "上海市",
                  "cityCode": "310100",
                  "cityName": "上海市",
                  "districtCode": "310115",
                  "districtName": "浦东新区",
                  "detailAddress": "世纪大道100号",
                  "tag": "家",
                  "isDefault": %s
                }
                """.formatted(isDefault);
    }

    private static AddressResponse response(Long id, boolean isDefault) {
        return new AddressResponse(
                id,
                "李小茶",
                "13800138000",
                "310000",
                "上海市",
                "310100",
                "上海市",
                "310115",
                "浦东新区",
                "世纪大道100号",
                "家",
                isDefault,
                LocalDateTime.of(2026, 10, 25, 10, 0),
                LocalDateTime.of(2026, 10, 25, 10, 0)
        );
    }
}