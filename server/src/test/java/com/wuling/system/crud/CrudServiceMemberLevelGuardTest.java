package com.wuling.system.crud;

import com.wuling.common.cache.AppConfigCacheService;
import com.wuling.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import org.mockito.ArgumentMatchers;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 会员等级权益写入守卫测试。
 *
 * <p><b>背景</b>：权益文案来自字典 member_benefit，其中「专属优惠券」需要在
 * 等级上绑定具体优惠券并填写数量。旧实现把这些字段交给前端自由填写、
 * 后端零校验，导致可以保存出「专属优惠券但没选券 / 没写数量」的脏数据，
 * 小程序端拿不到可发放的券。
 *
 * <p><b>本次收紧的规则</b>：
 * <ol>
 *   <li>权益项只允许 text / icon / couponId / count 四个字段；</li>
 *   <li>text 必填；</li>
 *   <li>text = 「专属优惠券」时必须带 couponId（正整数）与 count（正整数）；</li>
 *   <li>其他权益文案不得携带 count（数量只对专属优惠券有意义）。</li>
 * </ol>
 */
class CrudServiceMemberLevelGuardTest {

    private JdbcTemplate jdbcTemplate;
    private CrudService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new CrudService(jdbcTemplate, mock(AppConfigCacheService.class));
        lenient().when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        // create 尾部会 getOne 回读新记录（queryForList 需要非空才不抛「记录不存在」）
        lenient().when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(new java.util.LinkedHashMap<>(Map.of(
                        "id", 1, "level_code", "Lv1", "name", "时光卡", "benefits", "[]"))));
    }

    private Map<String, Object> payload(Object benefits) {
        return new java.util.LinkedHashMap<>(Map.of(
                "levelCode", "Lv1",
                "name", "时光卡",
                "benefits", benefits
        ));
    }

    @Test
    @DisplayName("专属优惠券必须绑定 couponId 且填写数量")
    void memberCouponRequiresCouponAndCount() {
        // 缺 couponId
        BusinessException noCoupon = assertThrows(BusinessException.class,
                () -> service.create("memberLevels",
                        payload(List.of(Map.of("text", "专属优惠券", "count", "2")))));
        assertTrue(noCoupon.getMessage().contains("优惠券"),
                "缺少 couponId 必须被拒绝且提示优惠券相关原因");

        // 缺数量
        BusinessException noCount = assertThrows(BusinessException.class,
                () -> service.create("memberLevels",
                        payload(List.of(Map.of("text", "专属优惠券", "couponId", 3)))));
        assertTrue(noCount.getMessage().contains("数量"),
                "缺少数量必须被拒绝且提示数量相关原因");

        verify(jdbcTemplate, never()).update(startsWith("insert into member_level"), any(Object[].class));
    }

    @Test
    @DisplayName("非专属优惠券的权益不得携带数量")
    void otherBenefitsMustNotCarryCount() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create("memberLevels",
                        payload(List.of(Map.of("text", "基础折扣", "count", "2")))));
        assertTrue(ex.getMessage().contains("数量"),
                "只有专属优惠券才允许数量，其他权益带数量必须被拒绝");
    }

    @Test
    @DisplayName("权益文案必填且不允许未知字段")
    void textRequiredAndUnknownFieldsRejected() {
        BusinessException emptyText = assertThrows(BusinessException.class,
                () -> service.create("memberLevels", payload(List.of(Map.of("icon", "star")))));
        assertTrue(emptyText.getMessage().contains("权益文案"), "权益文案必须必填");

        BusinessException unknown = assertThrows(BusinessException.class,
                () -> service.create("memberLevels",
                        payload(List.of(Map.of("text", "基础折扣", "hack", "x")))));
        assertTrue(unknown.getMessage().contains("字段"), "未知字段必须被拒绝，避免脏数据写入 JSON");
    }

    @Test
    @DisplayName("合法的权益组合可以保存")
    void validBenefitsAccepted() {
        assertDoesNotThrow(() -> service.create("memberLevels", payload(List.of(
                Map.of("text", "基础折扣", "icon", "badge-percent"),
                Map.of("text", "专属优惠券", "icon", "ticket-percent", "couponId", 3, "count", "2")
        ))));
    }

    @Test
    @DisplayName("memberLevels 资源必须登记 benefits 为可写 JSON 列")
    void registryExposesBenefits() {
        CrudRegistry.Resource def = CrudRegistry.get("memberLevels");
        assertNotNull(def, "应登记 memberLevels");
        assertTrue(def.writable().contains("benefits"), "benefits 必须可写");
    }
}
