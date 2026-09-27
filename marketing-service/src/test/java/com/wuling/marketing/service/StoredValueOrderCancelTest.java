package com.wuling.marketing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.common.exception.BusinessException;
import com.wuling.marketing.entity.StoredValueOrder;
import com.wuling.marketing.mapper.StoredValueOrderMapper;
import com.wuling.user.mapper.AppUserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoredValueOrderCancelTest {

    private final StoredValueOrderMapper orderMapper = mock(StoredValueOrderMapper.class);
    private final AppUserMapper appUserMapper = mock(AppUserMapper.class);
    private final StoredValueService service = new StoredValueService(
            null, null, orderMapper, null, appUserMapper);

    private StoredValueOrder order(Long id, Long userId, String payStatus) {
        StoredValueOrder o = new StoredValueOrder();
        o.setId(id);
        o.setOrderNo("CZ" + id);
        o.setUserId(userId);
        o.setPayStatus(payStatus);
        o.setAmount(20000L);
        return o;
    }

    @Test
    @DisplayName("本人取消未支付订单成功并置为取消状态")
    void cancelUnpaidSucceeds() {
        StoredValueOrder unpaid = order(1L, 9L, StoredValueService.PAY_UNPAID);
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(unpaid);
        when(orderMapper.update(any(StoredValueOrder.class), any(LambdaQueryWrapper.class))).thenReturn(1);

        StoredValueOrder result = service.cancelOrder(9L, "CZ1");

        assertNotNull(result);
        assertEquals("CANCELED", result.getStatus());
        verify(orderMapper).update(any(StoredValueOrder.class), any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("非本人取消返回 NOT_FOUND，不泄露订单存在性")
    void cancelOtherUserNotFound() {
        StoredValueOrder other = order(2L, 8L, StoredValueService.PAY_UNPAID);
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(other);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.cancelOrder(9L, "CZ2"));
        assertNotNull(ex);
        verify(orderMapper, never()).update(any(StoredValueOrder.class), any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("已支付订单不能取消")
    void cancelPaidRejected() {
        StoredValueOrder paid = order(3L, 9L, StoredValueService.PAY_PAID);
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(paid);

        assertThrows(BusinessException.class, () -> service.cancelOrder(9L, "CZ3"));
        verify(orderMapper, never()).update(any(StoredValueOrder.class), any(LambdaQueryWrapper.class));
    }
}
