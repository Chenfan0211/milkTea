package com.wuling.system.crud;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台列表「默认排序」约定的回归测试。
 *
 * <p>约定（见 {@link CrudRegistry} 类注释）：
 * <ol>
 *   <li>表里有 {@code sort} 列 → {@code sort asc, id asc}；</li>
 *   <li>表里没有 {@code sort} 列 → {@code create_time desc, id desc}（最新数据在最上）。</li>
 * </ol>
 *
 * <p><b>为什么需要这个测试</b>：排序写错不会报错，只会让列表顺序悄悄变成「id 升序」
 * （最老的排最前），属于看不出来的静默错误。历史上就存在大量 {@code id asc}/{@code id desc}，
 * 这次统一口径后必须锁住，防止新增资源时又退回旧写法。
 *
 * <p>表的列信息直接从 Flyway 迁移脚本解析，保证与真实 schema 一致，
 * 而不是在测试里再手抄一份列表（手抄的那份一定会过期）。
 */
class CrudRegistryOrderTest {

    /** 明确的例外：无 sort 列，但排序按业务语义而非创建时间。 */
    private static final Map<String, String> INTENTIONAL_EXCEPTIONS = Map.of(
            "dictTypes", "按字典编码分组排列更可读",
            "deviceBrewRecords", "业务时间是设备上报的 base_create_time，不是入库时间"
    );

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)CREATE\\s+TABLE(?:\\s+IF\\s+NOT\\s+EXISTS)?\\s+`?(\\w+)`?\\s*\\(");

    /**
     * 后加的列（如 gift_card_denomination.sort 由 V16 的 ALTER 补上）。
     * 只解析直白的 {@code ADD COLUMN x TYPE}，不解析用 PREPARE 包裹的条件式 ALTER
     * —— 那类写法本身就在判断列是否存在，属于幂等兜底，不影响本测试的结论。
     */
    private static final Pattern ALTER_ADD_COLUMN = Pattern.compile(
            "(?is)ALTER\\s+TABLE\\s+`?(\\w+)`?(.*?)(?=ALTER\\s+TABLE|CREATE\\s+TABLE|\\z)");
    private static final Pattern ADD_COLUMN = Pattern.compile(
            "(?is)ADD\\s+COLUMN\\s+`?(\\w+)`?\\s+[A-Za-z]");

    @Test
    @DisplayName("默认排序只有两种合法形态：sort asc 或 create_time desc")
    void orderByMustFollowConvention() {
        for (String resource : CrudRegistry.allResources()) {
            if (INTENTIONAL_EXCEPTIONS.containsKey(resource)) {
                continue;
            }
            CrudRegistry.Resource def = CrudRegistry.get(resource);
            String orderBy = def.orderBy();
            assertTrue(
                    orderBy.equals("sort asc, id asc") || orderBy.equals("create_time desc, id desc"),
                    String.format("资源 %s 的默认排序 %s 不符合约定：应为 sort asc, id asc 或 create_time desc, id desc",
                            resource, orderBy)
            );
        }
    }

    @Test
    @DisplayName("按 sort 排序的资源，其表必须有 sort 列")
    void sortOrderRequiresSortColumn() {
        Map<String, Set<String>> schema = readSchema();
        for (String resource : CrudRegistry.allResources()) {
            CrudRegistry.Resource def = CrudRegistry.get(resource);
            if (!def.orderBy().startsWith("sort ")) {
                continue;
            }
            Set<String> columns = schema.get(def.table());
            assertTrue(columns != null, String.format("资源 %s 的表 %s 在迁移脚本中不存在", resource, def.table()));
            assertTrue(
                    columns.contains("sort"),
                    String.format("资源 %s 按 sort 排序，但表 %s 没有 sort 列，SQL 会直接报错", resource, def.table())
            );
        }
    }

    @Test
    @DisplayName("按 create_time 倒序的资源，其表必须有 create_time 列")
    void createTimeOrderRequiresCreateTimeColumn() {
        Map<String, Set<String>> schema = readSchema();
        for (String resource : CrudRegistry.allResources()) {
            CrudRegistry.Resource def = CrudRegistry.get(resource);
            if (!def.orderBy().startsWith("create_time ")) {
                continue;
            }
            Set<String> columns = schema.get(def.table());
            assertTrue(columns != null, String.format("资源 %s 的表 %s 在迁移脚本中不存在", resource, def.table()));
            assertTrue(
                    columns.contains("create_time"),
                    String.format("资源 %s 按 create_time 排序，但表 %s 没有该列，SQL 会直接报错", resource, def.table())
            );
        }
    }

    @Test
    @DisplayName("不再有资源使用裸 id 排序（会被新数据/导入数据打乱语义）")
    void noBareIdOrdering() {
        for (String resource : CrudRegistry.allResources()) {
            String orderBy = CrudRegistry.get(resource).orderBy();
            assertFalse(
                    orderBy.equals("id asc") || orderBy.equals("id desc"),
                    String.format("资源 %s 仍在使用裸 id 排序 %s，应改为 sort 或 create_time 口径", resource, orderBy)
            );
        }
    }

    @Test
    @DisplayName("所有排序子句都能被 SqlGuard 正常解析（否则列表接口会在运行时抛异常）")
    void everyOrderByPassesSqlGuard() {
        for (String resource : CrudRegistry.allResources()) {
            String orderBy = CrudRegistry.get(resource).orderBy();
            // SqlGuard 会做两件事：按逗号拆分、校验每个「列名 + asc/desc」，
            // 然后统一小写并补回 ", " 分隔。任何不合规写法都会抛
            // IllegalArgumentException，那样列表接口一调用就 500。
            String guarded = com.wuling.common.sql.SqlGuard.orderBy(orderBy);
            String expected = orderBy.replaceAll("\\s+", " ").trim().toLowerCase();
            assertTrue(
                    guarded.equals(expected),
                    String.format("资源 %s 的排序子句 %s 经 SqlGuard 规范化后与预期不符：%s", resource, expected, guarded)
            );
        }
    }

    /** 解析所有迁移脚本，得到「表名 -> 列名集合」。 */
    private Map<String, Set<String>> readSchema() {
        Path migrationDir = Path.of("src", "main", "resources", "db", "migration");
        assertTrue(Files.isDirectory(migrationDir), "找不到迁移目录：" + migrationDir.toAbsolutePath());

        Map<String, Set<String>> schema = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(migrationDir)) {
            List<Path> sqlFiles = files
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".sql"))
                    .sorted()
                    .toList();
            for (Path file : sqlFiles) {
                parseFile(Files.readString(file, StandardCharsets.UTF_8), schema);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertFalse(schema.isEmpty(), "未能从迁移脚本解析出任何表结构");
        return schema;
    }

    private void parseFile(String sql, Map<String, Set<String>> schema) {
        Matcher matcher = CREATE_TABLE.matcher(sql);
        while (matcher.find()) {
            String table = matcher.group(1).toLowerCase();
            int start = matcher.end();
            int depth = 1;
            int i = start;
            while (i < sql.length() && depth > 0) {
                char c = sql.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
                i++;
            }
            String body = sql.substring(start, Math.max(start, i - 1));
            Set<String> columns = schema.computeIfAbsent(table, key -> new java.util.LinkedHashSet<>());
            for (String line : body.split("\n")) {
                Matcher column = Pattern.compile("^\\s*`?(\\w+)`?\\s+[A-Za-z]").matcher(line);
                if (column.find()) {
                    columns.add(column.group(1).toLowerCase());
                }
            }
        }
        // 建表之后新增的列：gift_card_denomination.sort 就是 V16 用 ALTER 补的。
        // 若只解析 CREATE TABLE，会误判这类表「没有 sort 列」。
        Matcher alter = ALTER_ADD_COLUMN.matcher(sql);
        while (alter.find()) {
            String table = alter.group(1).toLowerCase();
            Set<String> columns = schema.computeIfAbsent(table, key -> new java.util.LinkedHashSet<>());
            Matcher addColumn = ADD_COLUMN.matcher(alter.group(2));
            while (addColumn.find()) {
                columns.add(addColumn.group(1).toLowerCase());
            }
        }
    }
}
