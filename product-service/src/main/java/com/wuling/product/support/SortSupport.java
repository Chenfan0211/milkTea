package com.wuling.product.support;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;

import java.util.Map;

/**
 * 列表排序入参的统一处理。
 *
 * <p><b>为什么需要白名单</b>：后台列表的 {@code sortBy} 直接来自请求参数，
 * 若拼进 SQL 就是注入面；即使走 MyBatis-Plus 的 {@code orderBy}（列名不参数化），
 * 非法列名会在运行期抛 SQL 异常，表现为 500 而不是回落到默认顺序。
 * 这里用「列名 -> 列引用」的静态映射表做白名单，未命中一律回落默认排序。
 *
 * <p><b>为什么夹紧排序号</b>：{@code sort} 是运营手填的运营位，语义是
 * 「越小越靠前」。允许负数会让「0」不再是最靠前，允许超大值（如 2147483647）
 * 后续做「上移/下移」时容易溢出，因此统一夹到 0-9999。
 */
public final class SortSupport {

    /** 排序号上界：留给「上移/下移」时的自增空间，同时避免运算溢出。 */
    public static final int MAX_SORT = 9999;

    private SortSupport() {
    }

    /** 排序号归一：null / 负数按 0，超上界按上界。 */
    public static int clampSort(Integer sort) {
        if (sort == null || sort < 0) {
            return 0;
        }
        return Math.min(sort, MAX_SORT);
    }

    /** 把字符串排序号（表单可能传 String）解析并归一。 */
    public static int clampSort(Object rawSort) {
        if (rawSort == null) {
            return 0;
        }
        if (rawSort instanceof Number number) {
            return clampSort(number.intValue());
        }
        String text = String.valueOf(rawSort).trim();
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return clampSort(Integer.parseInt(text));
        } catch (NumberFormatException e) {
            // 非法输入不阻断保存：排序号只是展示顺序，回落默认位即可。
            return 0;
        }
    }

    /** 是否升序；仅显式 {@code desc} 才降序，其余（含空）按升序。 */
    public static boolean isAsc(String sortOrder) {
        return !"desc".equalsIgnoreCase(sortOrder == null ? "" : sortOrder.trim());
    }

    /**
     * 在白名单里查列引用；未命中返回 null，由调用方回落默认排序。
     *
     * @param whitelist 列名（前端列 key）到实体字段引用的映射
     * @param sortBy    请求里的排序列名
     */
    public static <T> SFunction<T, ?> resolve(Map<String, SFunction<T, ?>> whitelist, String sortBy) {
        if (sortBy == null) {
            return null;
        }
        String key = sortBy.trim();
        return key.isEmpty() ? null : whitelist.get(key);
    }
}
