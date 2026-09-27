package com.wuling.marketing.service;

import com.wuling.marketing.entity.GrowthRecord;
import com.wuling.marketing.mapper.GrowthRecordMapper;
import com.wuling.user.mapper.AppUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会员成长值（累计消费）累加逻辑测试（P0）。
 *
 * <p><b>核心不变量</b>：
 * <ol>
 *   <li>只累加「点单消费」订单（储值充值订单不发 OrderPaidEvent，天然被排除）；</li>
 *   <li>按实付金额（分）原子累加 app_user.total_spend；</li>
 *   <li>order_no 唯一索引幂等：重复消息不重复累加。</li>
 * </ol>
 */
class GrowthServiceTest {

    @Mock
    private AppUserMapper appUserMapper;
    @Mock
    private GrowthRecordMapper growthRecordMapper;

    private GrowthService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new GrowthService(appUserMapper, growthRecordMapper);
    }

    @Test
    @DisplayName("点单消费累加成长值并写流水")
    void addsSpendAndRecords() {
        when(appUserMapper.addTotalSpend(anyLong(), anyLong())).thenReturn(1);
        when(appUserMapper.getTotalSpend(anyLong())).thenReturn(3000L);

        long total = service.addSpend(9L, 1000L, "WX202609280001");

        assertEquals(3000L, total, "应返回累加后的累计消费");
        verify(appUserMapper).addTotalSpend(eq(9L), eq(1000L));
        verify(growthRecordMapper).insert(any(GrowthRecord.class));
    }

    @Test
    @DisplayName("重复订单号不重复累加（幂等）")
    void skipsDuplicateOrderNo() {
        when(appUserMapper.addTotalSpend(anyLong(), anyLong())).thenReturn(1);
        when(growthRecordMapper.insert(any(GrowthRecord.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        assertThrows(DuplicateKeyException.class,
                () -> service.addSpend(9L, 1000L, "WX202609280001"));
    }

    @Test
    @DisplayName("用户不存在或原子更新失败时不写流水")
    void abortsWhenAtomicUpdateFails() {
        when(appUserMapper.addTotalSpend(anyLong(), anyLong())).thenReturn(0);

        // 原子更新失败应抛异常（让事务回滚 + MQ 重试）
        assertThrows(IllegalStateException.class,
                () -> service.addSpend(9L, 1000L, "WX202609280001"));
        verify(growthRecordMapper, never()).insert(any(GrowthRecord.class));
    }
}
