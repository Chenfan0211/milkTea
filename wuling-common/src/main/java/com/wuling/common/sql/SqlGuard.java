package com.wuling.common.sql;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * SQL 标识符安全网关。
 *
 * <p>背景：见 docs/微服务改造方案.md 第三章。项目中原有若干动态 SQL 拼接点
 * （{@code CrudService}、{@code AdminAuthQueryController}），虽然当前拼进去的
 * 表名/列名/排序都是代码常量，但缺少统一兜底，一旦有人把配置或用户输入接进来即失守。
 *
 * <p>铁律：<b>任何拼进 SQL 的表名 / 列名 / 排序字段，必须先过这里。</b>
 * 值仍然一律使用 {@code ?} 占位符绑定，本类只负责「标识符」这一类无法参数化的部分。
 *
 * <pre>{@code
 * String sql = "select * from " + SqlGuard.ident(table)
 *            + SqlGuard.orderBy("sort asc, id desc");
 * }</pre>
 */
public final class SqlGuard {

    /** 合法标识符：小写字母开头，仅含小写字母、数字、下划线，最长 64 */
    private static final Pattern IDENT = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    /** 排序方向白名单 */
    private static final Set<String> DIRECTIONS = Set.of("asc", "desc");

    private SqlGuard() {
    }

    /**
     * 校验单个标识符（表名、列名）。
     *
     * @param name 待校验标识符
     * @return 原样返回，便于链式拼接
     * @throws IllegalArgumentException 标识符非法
     */
    public static String ident(String name) {
        if (name == null || !IDENT.matcher(name).matches()) {
            throw new IllegalArgumentException("非法 SQL 标识符: " + name);
        }
        return name;
    }

    /**
     * 校验并规范化 order by 子句，形如 {@code "sort asc, id desc"}。
     *
     * @param clause 排序子句
     * @return 规范化后的子句（列名小写、方向小写）
     * @throws IllegalArgumentException 子句格式非法或方向不在白名单内
     */
    public static String orderBy(String clause) {
        if (clause == null || clause.isBlank()) {
            throw new IllegalArgumentException("排序子句不能为空");
        }
        StringBuilder sb = new StringBuilder();
        for (String part : clause.split(",")) {
            String[] tokens = part.trim().split("\\s+");
            if (tokens.length != 2) {
                throw new IllegalArgumentException("非法排序子句: " + clause);
            }
            String column = tokens[0].toLowerCase();
            String direction = tokens[1].toLowerCase();
            if (!DIRECTIONS.contains(direction)) {
                throw new IllegalArgumentException("非法排序方向: " + clause);
            }
            sb.append(ident(column)).append(' ').append(direction).append(", ");
        }
        return sb.substring(0, sb.length() - 2);
    }

    /**
     * 校验标识符是否合法（不抛异常版本）。
     *
     * @param name 待校验标识符
     * @return true=合法
     */
    public static boolean isValid(String name) {
        return name != null && IDENT.matcher(name).matches();
    }
}
