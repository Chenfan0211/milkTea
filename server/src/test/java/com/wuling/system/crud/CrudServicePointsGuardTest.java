package com.wuling.system.crud;

import com.wuling.common.api.ResultCode;
import com.wuling.common.cache.AppConfigCacheService;
import com.wuling.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrudServicePointsGuardTest {

    private JdbcTemplate jdbcTemplate;
    private CrudService service;
    private List<String> updateSql;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new CrudService(jdbcTemplate, mock(AppConfigCacheService.class));
        updateSql = new ArrayList<>();
        lenient().when(jdbcTemplate.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            updateSql.add(invocation.getArgument(0));
            return 1;
        });
    }

    @Test
    void registryShouldExposePointsCategoryAndPointsProductBindingFields() {
        CrudRegistry.Resource products = CrudRegistry.get("pointsProducts");
        assertNotNull(products, "应登记 pointsProducts");
        assertTrue(products.writable().containsAll(List.of(
                        "purchase_limit", "display_type", "coupon_amount", "coupon_condition",
                        "badge_in_image", "coupon_id")),
                "积分商品白名单必须放行限购次数和优惠券绑定字段");
        assertEquals(List.of("category", "status"), products.filterable(),
                "pointsProducts 必须支持按 category、status 精确筛选");

        CrudRegistry.Resource categories = CrudRegistry.get("pointsCategories");
        assertNotNull(categories, "应登记 pointsCategories");
        assertEquals("points_category", categories.table());
        assertEquals(List.of("code", "name", "sort", "enabled"), categories.writable());
        assertEquals(List.of("code", "name"), categories.searchable());
        assertEquals("sort asc, id asc", categories.orderBy());
        assertEquals(List.of("enabled"), categories.filterable());
        assertFalse(categories.writable().contains("system_locked"),
                "system_locked 是服务端锁定标记，绝不能由通用 CRUD 直接写入");
    }

    @Test
    void creatingCategoryRequiresCodeAndName() {
        BusinessException missingCode = assertThrows(BusinessException.class,
                () -> service.create("pointsCategories", Map.of("name", "宠物公益专区")));
        assertTrue(missingCode.getMessage().contains("编码"));

        BusinessException missingName = assertThrows(BusinessException.class,
                () -> service.create("pointsCategories", Map.of("code", "pet")));
        assertTrue(missingName.getMessage().contains("名称"));

        verify(jdbcTemplate, never()).update(startsWith("insert into points_category"), any(Object[].class));
    }

    @Test
    void creatingAllCategoryShouldBeRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create("pointsCategories", Map.of("code", "all", "name", "全部")));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        assertTrue(ex.getMessage().contains("all") || ex.getMessage().contains("保留"));
        verify(jdbcTemplate, never()).update(startsWith("insert into points_category"), any(Object[].class));
    }

    @Test
    void categoryCodeCannotBeChangedAfterCreation() {
        stubCategoryBefore(categoryRow(8L, "pet", "宠物公益专区", 10, 0));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update("pointsCategories", 8L, Map.of("code", "pets")));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        assertTrue(ex.getMessage().contains("编码"));
        verify(jdbcTemplate, never()).update(startsWith("update points_product set category"), any(Object[].class));
        verify(jdbcTemplate, never()).update(startsWith("update points_category set"), any(Object[].class));
    }

    @Test
    void systemCategoryCanEditNameSortAndEnabled() {
        stubCategorySequence(categoryRow(7L, "coupon", "优惠券区", 10, 1),
                categoryRow(7L, "coupon", "优惠券专区", 20, 1));

        service.update("pointsCategories", 7L,
                Map.of("name", "优惠券专区", "sort", 20, "enabled", 0));

        assertTrue(updateSql.stream().anyMatch(sql -> sql.startsWith("update points_category set")),
                "系统分类应允许修改名称、排序和启停状态");
    }

    @Test
    void systemCategoryCannotBeDeleted() {
        stubCategoryBefore(categoryRow(7L, "coupon", "优惠券区", 10, 1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.delete("pointsCategories", 7L));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        assertTrue(ex.getMessage().contains("不能删除") || ex.getMessage().contains("不允许删除"));
        verify(jdbcTemplate, never()).update(startsWith("update points_category set deleted = 1"), any(Object[].class));
    }

    @Test
    void deletingCategoryWithProductsShouldBeRejected() {
        stubCategoryBefore(categoryRow(8L, "pet", "宠物公益专区", 20, 0));
        stubCount("from points_product", 1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.delete("pointsCategories", 8L));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        assertTrue(ex.getMessage().contains("积分商品"));
        verify(jdbcTemplate, never()).update(startsWith("update points_category set deleted = 1"), any(Object[].class));
    }

    @Test
    void deletingCategoryWithoutProductsShouldSoftDelete() {
        stubCategoryBefore(categoryRow(8L, "pet", "宠物公益专区", 20, 0));
        stubCount("from points_product", 0);

        service.delete("pointsCategories", 8L);

        assertTrue(updateSql.stream().anyMatch(sql -> sql.startsWith("update points_category set deleted = 1")),
                "无有效商品引用的分类应允许逻辑删除");
    }

    @Test
    void deletingCouponBoundToProductsShouldBeRejected() {
        when(jdbcTemplate.queryForList(startsWith("select * from coupon"), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 3L, "code", "coupon-001", "status", "enabled")));
        stubCount("from points_product", 1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.delete("coupons", 3L));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        assertTrue(ex.getMessage().contains("解绑") || ex.getMessage().contains("停用"));
        verify(jdbcTemplate, never()).update(startsWith("update coupon set deleted = 1"), any(Object[].class));
    }

    @Test
    void creatingCouponCategoryProductMustBindCoupon() {
        stubCount("from points_category", 1);
        stubCount("from coupon", 0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create("pointsProducts", Map.of(
                        "name", "优惠券商品", "points", 100, "stock", 1, "category", "coupon")));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        assertTrue(ex.getMessage().contains("优惠券"));
    }

    @Test
    void couponCategoryProductMustBindEnabledCoupon() {
        when(jdbcTemplate.queryForList(startsWith("select * from points_product"), any(Object[].class)))
                .thenReturn(List.of(productRow(11L, "pet", null)));
        stubCount("from points_category", 1);
        stubCount("from coupon", 0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update("pointsProducts", 11L, Map.of("category", "coupon")));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        assertTrue(ex.getMessage().contains("优惠券"));
        verify(jdbcTemplate, never()).update(startsWith("update points_product set"), any(Object[].class));
    }

    @Test
    void nonCouponCategoryProductCannotBindCoupon() {
        when(jdbcTemplate.queryForList(startsWith("select * from points_product"), any(Object[].class)))
                .thenReturn(List.of(productRow(11L, "pet", null)));
        stubCount("from points_category", 1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update("pointsProducts", 11L,
                        Map.of("category", "pet", "couponId", 3L)));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        assertTrue(ex.getMessage().contains("不能绑定") || ex.getMessage().contains("禁止绑定"));
        verify(jdbcTemplate, never()).update(startsWith("update points_product set"), any(Object[].class));
    }

    @Test
    void partialProductUpdateShouldReuseExistingCouponBinding() {
        when(jdbcTemplate.queryForList(startsWith("select * from points_product"), any(Object[].class)))
                .thenReturn(List.of(productRow(11L, "coupon", 3L)))
                .thenReturn(List.of(productRow(11L, "coupon", 3L)));
        stubCount("from points_category", 1);
        stubCount("from coupon", 1);

        service.update("pointsProducts", 11L, Map.of("purchaseLimit", 2));

        assertTrue(updateSql.stream().anyMatch(sql -> sql.startsWith("update points_product set")),
                "只更新限购次数时，应复用旧分类和旧优惠券绑定完成校验");
    }

    private void stubCategoryBefore(Map<String, Object> row) {
        lenient().when(jdbcTemplate.queryForList(startsWith("select * from points_category"), any(Object[].class)))
                .thenReturn(List.of(row));
    }

    private void stubCategorySequence(Map<String, Object> before, Map<String, Object> after) {
        when(jdbcTemplate.queryForList(startsWith("select * from points_category"), any(Object[].class)))
                .thenReturn(List.of(before), List.of(after));
    }

    private void stubCount(String sqlFragment, int count) {
        lenient().when(jdbcTemplate.queryForObject(contains(sqlFragment), eq(Integer.class), any(Object[].class)))
                .thenReturn(count);
    }

    private int indexOfSql(String fragment) {
        for (int i = 0; i < updateSql.size(); i++) {
            if (updateSql.get(i).startsWith(fragment)) {
                return i;
            }
        }
        return -1;
    }

    private Map<String, Object> categoryRow(long id, String code, String name, int sort, int systemLocked) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("code", code);
        row.put("name", name);
        row.put("sort", sort);
        row.put("enabled", 1);
        row.put("systemLocked", systemLocked);
        return row;
    }

    private Map<String, Object> productRow(long id, String category, Long couponId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("code", "points-" + id);
        row.put("name", "积分商品");
        row.put("category", category);
        row.put("couponId", couponId);
        return row;
    }
}