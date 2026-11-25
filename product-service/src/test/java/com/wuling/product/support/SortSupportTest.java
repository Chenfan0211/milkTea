package com.wuling.product.support;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SortSupportTest {

    @Test
    @DisplayName("排序号夹到 0-9999：负数与空值按 0，超大值按上界")
    void clampSortNormalizesRange() {
        assertEquals(0, SortSupport.clampSort((Integer) null));
        assertEquals(0, SortSupport.clampSort(-5));
        assertEquals(0, SortSupport.clampSort(0));
        assertEquals(8, SortSupport.clampSort(8));
        assertEquals(SortSupport.MAX_SORT, SortSupport.clampSort(100000));
    }

    @Test
    @DisplayName("字符串排序号可解析；非法文本回落 0 而不是抛异常")
    void clampSortAcceptsStrings() {
        assertEquals(12, SortSupport.clampSort("12"));
        assertEquals(12, SortSupport.clampSort(" 12 "));
        assertEquals(0, SortSupport.clampSort("abc"));
        assertEquals(0, SortSupport.clampSort(""));
        assertEquals(0, SortSupport.clampSort((Object) null));
    }

    @Test
    @DisplayName("仅显式 desc 才降序，其余按升序")
    void isAscDefaultsToAscending() {
        assertTrue(SortSupport.isAsc(null));
        assertTrue(SortSupport.isAsc(""));
        assertTrue(SortSupport.isAsc("asc"));
        assertTrue(SortSupport.isAsc("ASC"));
        assertFalse(SortSupport.isAsc("desc"));
        assertFalse(SortSupport.isAsc(" DESC "));
    }

    @Test
    @DisplayName("白名单未命中返回 null，调用方回落默认排序")
    void resolveRejectsUnknownColumns() {
        SFunction<Object, ?> sortColumn = value -> value;
        Map<String, SFunction<Object, ?>> whitelist = Map.of("sort", sortColumn);

        assertEquals(sortColumn, SortSupport.resolve(whitelist, "sort"));
        assertEquals(sortColumn, SortSupport.resolve(whitelist, " sort "));
        assertNull(SortSupport.resolve(whitelist, "price desc; drop table product"));
        assertNull(SortSupport.resolve(whitelist, ""));
        assertNull(SortSupport.resolve(whitelist, null));
    }
}
