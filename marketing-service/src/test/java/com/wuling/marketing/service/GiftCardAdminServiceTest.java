package com.wuling.marketing.service;

import com.wuling.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GiftCardAdminServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final GiftCardAdminService service = new GiftCardAdminService(jdbcTemplate);

    @Test
    @DisplayName("卡面 faceId 取有效面额中的最小 id")
    void listFacesUsesMinimumEffectiveDenominationId() {
        when(jdbcTemplate.queryForList(contains("from gift_card_denomination d"), any(Object[].class)))
                .thenReturn(List.of(
                        Map.of("id", 30L, "group_id", "popular", "card_name", "抹茶卡",
                                "card_image", "/assets/images/3x/gift-card-matcha.jpg",
                                "name", "抹茶卡 500元礼品卡", "amount", 50000L,
                                "sale_price", 45000L, "sort", 5, "status", "enabled",
                                "display_group_title", "人气礼品卡"),
                        Map.of("id", 10L, "group_id", "popular", "card_name", "抹茶卡",
                                "card_image", "/assets/images/3x/gift-card-matcha.jpg",
                                "name", "抹茶卡 100元礼品卡", "amount", 10000L,
                                "sale_price", 9000L, "sort", 5, "status", "enabled",
                                "display_group_title", "人气礼品卡")));
        when(jdbcTemplate.queryForList(contains("select denomination_id from gift_card"), any(Object[].class)))
                .thenReturn(List.of());

        Map<String, Object> face = service.listFaces(1, 10, null).getRecords().get(0);

        assertEquals(10L, face.get("faceId"));
    }

    @Test
    @DisplayName("分组非空时禁止删除")
    void deleteRejectsNonEmptyGroup() {
        when(jdbcTemplate.queryForObject(
                contains("select count(distinct card_name) from gift_card_denomination"),
                eq(Long.class), eq("popular"))).thenReturn(2L);

        assertThrows(BusinessException.class, () -> service.deleteGroup("popular"));

        verify(jdbcTemplate, never()).update(contains("update gift_card_group set deleted = 1"),
                org.mockito.ArgumentMatchers.<Object>any());
    }

    @Test
    @DisplayName("新增卡面时所有面额默认下架")
    void createFaceDefaultsDenominationsToDisabled() {
        when(jdbcTemplate.queryForObject(contains("select name from gift_card_group"),
                eq(String.class), eq("popular"))).thenReturn("人气礼品卡");
        when(jdbcTemplate.queryForObject(contains("select count(*) from gift_card_denomination"),
                eq(Long.class), eq("popular"), eq("抹茶卡"))).thenReturn(0L);

        service.createFace(new GiftCardAdminService.SaveGiftCardFaceRequest(
                "popular", "抹茶卡", "/assets/images/3x/gift-card-matcha.jpg", 5,
                List.of(
                        new GiftCardAdminService.DenominationRequest(null, 10000L, 9000L),
                        new GiftCardAdminService.DenominationRequest(null, 20000L, 18000L))));

        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("insert into gift_card_denomination")
                        && sql.contains("'disabled'")),
                anyString(), eq("popular"), eq("人气礼品卡"), eq("抹茶卡"),
                eq("/assets/images/3x/gift-card-matcha.jpg"), anyString(),
                eq(10000L), eq(9000L), eq(5));
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("insert into gift_card_denomination")
                        && sql.contains("'disabled'")),
                anyString(), eq("popular"), eq("人气礼品卡"), eq("抹茶卡"),
                eq("/assets/images/3x/gift-card-matcha.jpg"), anyString(),
                eq(20000L), eq(18000L), eq(5));
    }

    @Test
    @DisplayName("整卡状态更新会作用于该卡面所有有效面额")
    void statusUpdatesWholeFace() {
        when(jdbcTemplate.queryForList(contains("where id = ? and deleted = 0"), eq(22L)))
                .thenReturn(List.of(Map.of("group_id", "popular", "card_name", "抹茶卡")));

        service.setFaceStatus(22L, false);

        verify(jdbcTemplate).update(contains("set status = ?"),
                eq("disabled"), eq("popular"), eq("抹茶卡"));
    }

    @Test
    @DisplayName("已被礼品卡或订单引用的面额禁止改 amount")
    void updateRejectsChangingReferencedAmount() {
        when(jdbcTemplate.queryForList(contains("where id = ? and deleted = 0"), eq(22L)))
                .thenReturn(List.of(Map.of("group_id", "popular", "card_name", "抹茶卡")));
        when(jdbcTemplate.queryForObject(contains("select name from gift_card_group"),
                eq(String.class), eq("popular"))).thenReturn("人气礼品卡");
        when(jdbcTemplate.queryForList(contains("where group_id = ? and card_name = ? and deleted = 0"),
                eq("popular"), eq("抹茶卡"))).thenReturn(List.of(
                Map.of("id", 22L, "amount", 10000L, "sale_price", 9000L, "status", "enabled")));
        when(jdbcTemplate.queryForObject(contains("from gift_card where denomination_id = ?"),
                eq(Long.class), eq(22L), eq(22L))).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateFace(22L, new GiftCardAdminService.SaveGiftCardFaceRequest(
                        "popular", "抹茶卡", "/assets/images/3x/gift-card-matcha.jpg", 5,
                        List.of(new GiftCardAdminService.DenominationRequest(22L, 20000L, 18000L)))));

        assertTrue(ex.getMessage().contains("引用"), ex.getMessage());
        verify(jdbcTemplate, never()).update(contains("set group_title = ?"),
                org.mockito.ArgumentMatchers.<Object>any());
    }

    @Test
    @DisplayName("编辑卡面时允许迁移到请求指定分组")
    void updateFaceMovesCardToRequestedGroup() {
        when(jdbcTemplate.queryForList(contains("where id = ? and deleted = 0"), eq(22L)))
                .thenReturn(List.of(Map.of("group_id", "popular", "card_name", "抹茶卡")));
        when(jdbcTemplate.queryForObject(contains("select name from gift_card_group"),
                eq(String.class), eq("limited"))).thenReturn("限定心意卡");
        when(jdbcTemplate.queryForObject(contains("select count(*) from gift_card_denomination"),
                eq(Long.class), eq("limited"), eq("抹茶卡"), eq("popular"), eq("抹茶卡"))).thenReturn(0L);
        when(jdbcTemplate.queryForList(contains("select id, code, amount, sale_price, status"),
                eq("popular"), eq("抹茶卡"))).thenReturn(List.of(
                Map.of("id", 22L, "amount", 10000L, "sale_price", 9000L, "status", "enabled")));
        when(jdbcTemplate.queryForObject(contains("from gift_card where denomination_id"),
                eq(Long.class), eq(22L), eq(22L))).thenReturn(0L);

        service.updateFace(22L, new GiftCardAdminService.SaveGiftCardFaceRequest(
                "limited", "抹茶卡", "/assets/images/3x/gift-card-matcha.jpg", 3,
                List.of(new GiftCardAdminService.DenominationRequest(22L, 10000L, 9000L))));

        verify(jdbcTemplate).update(contains("set group_id = ?"),
                eq("limited"), eq("限定心意卡"), eq("抹茶卡"),
                eq("/assets/images/3x/gift-card-matcha.jpg"), anyString(),
                eq(10000L), eq(9000L), eq(3), eq(22L));
    }
}
