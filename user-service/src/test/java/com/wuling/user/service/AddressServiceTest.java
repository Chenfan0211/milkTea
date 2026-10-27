package com.wuling.user.service;

import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.user.dto.AddressRequest;
import com.wuling.user.dto.AddressResponse;
import com.wuling.user.dto.AddressSnapshotResponse;
import com.wuling.user.entity.AppUserAddress;
import com.wuling.user.mapper.AppUserAddressMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AddressServiceTest {

    private AppUserAddressMapper mapper;
    private AddressService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AppUserAddressMapper.class);
        service = new AddressService(mapper);
    }

    @Test
    @DisplayName("列表只读取当前用户的地址")
    void listOnlyReadsCurrentUsersAddresses() {
        when(mapper.selectOwnedList(7L)).thenReturn(List.of(
                address(11L, 7L, 1, LocalDateTime.of(2026, 10, 25, 10, 0)),
                address(12L, 7L, 0, LocalDateTime.of(2026, 10, 24, 10, 0))
        ));

        List<AddressResponse> result = service.list(7L);

        assertEquals(List.of(11L, 12L), result.stream().map(AddressResponse::id).toList());
        verify(mapper).selectOwnedList(7L);
    }

    @Test
    @DisplayName("首条地址自动成为默认地址")
    void firstAddressBecomesDefault() {
        when(mapper.lockUser(7L)).thenReturn(7L);
        when(mapper.countOwned(7L)).thenReturn(0L);
        when(mapper.insert(any(AppUserAddress.class))).thenAnswer(invocation -> {
            AppUserAddress address = invocation.getArgument(0);
            address.setId(11L);
            return 1;
        });
        AddressRequest request = validRequest();
        request.setIsDefault(false);

        AddressResponse result = service.create(7L, request);

        ArgumentCaptor<AppUserAddress> captor = ArgumentCaptor.forClass(AppUserAddress.class);
        verify(mapper).insert(captor.capture());
        AppUserAddress saved = captor.getValue();
        assertEquals(7L, saved.getUserId());
        assertEquals("李小茶", saved.getRecipientName());
        assertEquals(1, saved.getIsDefault());
        assertTrue(result.isDefault());
        verify(mapper, never()).clearOwnedDefaults(7L);
    }

    @Test
    @DisplayName("新增地址请求默认时先清除旧默认地址")
    void createDefaultClearsPreviousDefault() {
        when(mapper.lockUser(7L)).thenReturn(7L);
        when(mapper.countOwned(7L)).thenReturn(2L);
        when(mapper.insert(any(AppUserAddress.class))).thenReturn(1);
        AddressRequest request = validRequest();
        request.setIsDefault(true);

        service.create(7L, request);

        InOrder order = inOrder(mapper);
        order.verify(mapper).clearOwnedDefaults(7L);
        order.verify(mapper).insert(any(AppUserAddress.class));
    }

    @Test
    @DisplayName("单用户已有20条有效地址时拒绝新增")
    void rejectsTwentyFirstAddress() {
        when(mapper.lockUser(7L)).thenReturn(7L);
        when(mapper.countOwned(7L)).thenReturn(20L);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create(7L, validRequest()));

        assertEquals(ResultCode.BAD_REQUEST, error.getCode());
        assertEquals("收货地址最多保存20条", error.getMessage());
        verify(mapper, never()).insert(any(AppUserAddress.class));
    }

    @Test
    @DisplayName("编辑他人地址统一返回不存在")
    void updateRejectsAddressNotOwnedByCurrentUser() {
        when(mapper.selectOwnedById(99L, 7L)).thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.update(7L, 99L, validRequest()));

        assertEquals(ResultCode.NOT_FOUND, error.getCode());
        verify(mapper, never()).updateById(any(AppUserAddress.class));
    }

    @Test
    @DisplayName("编辑默认地址时不能通过 isDefault=false 取消唯一默认")
    void updateCannotUnsetCurrentDefault() {
        AppUserAddress existing = address(11L, 7L, 1, LocalDateTime.now());
        when(mapper.selectOwnedById(11L, 7L)).thenReturn(existing);
        AddressRequest request = validRequest();
        request.setIsDefault(false);

        AddressResponse result = service.update(7L, 11L, request);

        assertTrue(result.isDefault());
        assertEquals(1, existing.getIsDefault());
        verify(mapper).updateById(existing);
        verify(mapper, never()).clearOwnedDefaults(7L);
    }

    @Test
    @DisplayName("设默认时事务内先清除旧默认再设置目标地址")
    void setDefaultClearsOldDefaultBeforeMarkingTarget() {
        when(mapper.selectOwnedById(12L, 7L)).thenReturn(address(12L, 7L, 0, LocalDateTime.now()));
        when(mapper.clearOwnedDefaults(7L)).thenReturn(1);
        when(mapper.markOwnedDefault(12L, 7L)).thenReturn(1);

        service.setDefault(7L, 12L);

        verify(mapper).lockUser(7L);

        InOrder order = inOrder(mapper);
        order.verify(mapper).clearOwnedDefaults(7L);
        order.verify(mapper).markOwnedDefault(12L, 7L);
    }

    @Test
    @DisplayName("删除默认地址后自动把最近更新的一条设为默认")
    void deleteDefaultPromotesMostRecentlyUpdatedAddress() {
        AppUserAddress deleted = address(11L, 7L, 1, LocalDateTime.of(2026, 10, 23, 10, 0));
        AppUserAddress recent = address(13L, 7L, 0, LocalDateTime.of(2026, 10, 24, 10, 0));
        when(mapper.selectOwnedById(11L, 7L)).thenReturn(deleted);
        when(mapper.logicalDeleteOwned(11L, 7L)).thenReturn(1);
        when(mapper.selectMostRecentOwned(7L)).thenReturn(recent);
        when(mapper.markOwnedDefault(13L, 7L)).thenReturn(1);

        service.delete(7L, 11L);

        verify(mapper).logicalDeleteOwned(11L, 7L);
        verify(mapper).selectMostRecentOwned(7L);
        verify(mapper).markOwnedDefault(13L, 7L);
    }

    @Test
    @DisplayName("删除非默认地址不改变现有默认地址")
    void deleteNonDefaultDoesNotPromoteAnotherAddress() {
        when(mapper.selectOwnedById(12L, 7L)).thenReturn(address(12L, 7L, 0, LocalDateTime.now()));
        when(mapper.logicalDeleteOwned(12L, 7L)).thenReturn(1);

        service.delete(7L, 12L);

        verify(mapper, never()).selectMostRecentOwned(7L);
        verify(mapper, never()).markOwnedDefault(any(), any());
    }

    @Test
    @DisplayName("删除他人地址统一返回不存在")
    void deleteRejectsAddressNotOwnedByCurrentUser() {
        when(mapper.selectOwnedById(99L, 7L)).thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.delete(7L, 99L));

        assertEquals(ResultCode.NOT_FOUND, error.getCode());
        verify(mapper, never()).logicalDeleteOwned(any(), any());
    }

    @Test
    @DisplayName("内部快照按地址与用户双条件读取并只返回下单所需字段")
    void snapshotRequiresOwnershipAndReturnsShippingFields() {
        when(mapper.selectOwnedById(11L, 7L)).thenReturn(address(11L, 7L, 1, LocalDateTime.now()));

        AddressSnapshotResponse result = service.snapshot(7L, 11L);

        assertEquals("李小茶", result.recipientName());
        assertEquals("13800138000", result.phone());
        assertEquals("上海市", result.provinceName());
        assertEquals("上海市", result.cityName());
        assertEquals("浦东新区", result.districtName());
        assertEquals("世纪大道100号", result.detailAddress());
        verify(mapper).selectOwnedById(11L, 7L);
    }

    @Test
    @DisplayName("收件人姓名必填")
    void rejectsBlankRecipientName() {
        AddressRequest request = validRequest();
        request.setRecipientName("  ");

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create(7L, request));

        assertEquals("请输入收件人姓名", error.getMessage());
        verify(mapper, never()).lockUser(7L);
    }

    @Test
    @DisplayName("手机号必须为11位中国大陆手机号")
    void rejectsInvalidPhone() {
        AddressRequest request = validRequest();
        request.setPhone("12345");

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create(7L, request));

        assertEquals("请输入正确的手机号", error.getMessage());
    }

    @Test
    @DisplayName("省市区和详细地址超过数据库长度时拒绝保存")
    void rejectsOversizedRegionAndDetailAddress() {
        AddressRequest tooLongProvince = validRequest();
        tooLongProvince.setProvinceName("省".repeat(65));
        BusinessException provinceError = assertThrows(BusinessException.class,
                () -> service.create(7L, tooLongProvince));
        assertEquals("省份名称不能超过64个字符", provinceError.getMessage());

        AddressRequest tooLongDetail = validRequest();
        tooLongDetail.setDetailAddress("址".repeat(256));
        BusinessException detailError = assertThrows(BusinessException.class,
                () -> service.create(7L, tooLongDetail));
        assertEquals("详细地址不能超过255个字符", detailError.getMessage());
    }

    @Test
    @DisplayName("地址响应保留默认标记")
    void responseCarriesDefaultState() {
        AddressResponse response = AddressResponse.from(address(11L, 7L, 1, LocalDateTime.now()));
        assertTrue(response.isDefault());
        assertFalse(AddressResponse.from(address(12L, 7L, 0, LocalDateTime.now())).isDefault());
    }

    private static AddressRequest validRequest() {
        AddressRequest request = new AddressRequest();
        request.setRecipientName(" 李小茶 ");
        request.setPhone(" 13800138000 ");
        request.setProvinceCode("310000");
        request.setProvinceName("上海市");
        request.setCityCode("310100");
        request.setCityName("上海市");
        request.setDistrictCode("310115");
        request.setDistrictName("浦东新区");
        request.setDetailAddress("世纪大道100号");
        request.setTag("家");
        return request;
    }

    private static AppUserAddress address(Long id, Long userId, int isDefault, LocalDateTime updateTime) {
        AppUserAddress address = new AppUserAddress();
        address.setId(id);
        address.setUserId(userId);
        address.setRecipientName("李小茶");
        address.setPhone("13800138000");
        address.setProvinceCode("310000");
        address.setProvinceName("上海市");
        address.setCityCode("310100");
        address.setCityName("上海市");
        address.setDistrictCode("310115");
        address.setDistrictName("浦东新区");
        address.setDetailAddress("世纪大道100号");
        address.setTag("家");
        address.setIsDefault(isDefault);
        address.setCreateTime(updateTime.minusDays(1));
        address.setUpdateTime(updateTime);
        address.setDeleted(0);
        return address;
    }
}