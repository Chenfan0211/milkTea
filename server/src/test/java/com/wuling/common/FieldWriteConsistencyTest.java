package com.wuling.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字段写库一致性守卫（CI 检查）。
 *
 * <p><b>为什么需要这个测试</b>：
 * 后台的配置类页面走「通用 CRUD」——前端提交字段，后端按 {@code CrudRegistry} 白名单过滤。
 * 若前端表单字段名与数据库列名不一致，字段会被 {@code CrudService.filterWritable()}
 * <b>静默丢弃</b>，接口仍返回 200，表现为「填了数据但页面看不到反应」——
 * 这类问题极难排查，2026-09 曾一次性发现 12 个页面中招。
 *
 * <p>本测试把这种「字段名漂移」变成<b>编译期可发现的问题</b>：
 * 静态解析前端页面表单字段 → 比对 CrudRegistry 白名单 → 不一致即失败。
 *
 * <p><b>覆盖范围</b>：
 * 只检查「真正调用 store.add/update/patch('资源名', ...)」的页面。
 * 走专用接口的页面（如门店、渠道、商品）不参与校验，避免误报。
 *
 * <p><b>专用接口路径见 {@link DedicatedEndpointConsistencyTest}</b>：
 * 2026-09-25 的线上 Bug（经营角色显示英文 STORE / 绑定主体列为空 / 解绑无效）
 * 恰好落在本测试的盲区内 —— 它走的是 SubjectBindingController 专用接口，
 * 既不在 CrudRegistry 白名单，也不被本测试扫描。该路径已由 DedicatedEndpointConsistencyTest 覆盖。
 */
class FieldWriteConsistencyTest {

    /** 仓库根目录（测试的工作目录是 server 模块） */
    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        // 从 server 模块向上找到含 src/views 的目录
        for (Path p = cwd; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("src/views"))) {
                return p;
            }
        }
        for (Path p = cwd; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("../src/views"))) {
                return p.getParent();
            }
        }
        return cwd;
    }

    /** CrudRegistry 中的一个资源配置 */
    private record Resource(String name, String table, Set<String> writable) {
    }

    /**
     * 解析 CrudRegistry.java 中的白名单。
     *
     * <p>匹配形如：
     * {@code Map.entry("users", new Resource("users", "app_user", List.of("a","b"), ...))}
     */
    private static Map<String, Resource> parseRegistry(Path registryFile) throws IOException {
        String src = Files.readString(registryFile, StandardCharsets.UTF_8);
        Pattern p = Pattern.compile(
                "Map\\.entry\\(\\s*\"(\\w+)\"\\s*,\\s*new\\s+Resource\\(\\s*\"\\w+\"\\s*,\\s*\"(\\w+)\"\\s*,\\s*List\\.of\\(([^)]*)\\)",
                Pattern.DOTALL);
        Map<String, Resource> out = new LinkedHashMap<>();
        Matcher m = p.matcher(src);
        while (m.find()) {
            Set<String> cols = new LinkedHashSet<>();
            Matcher cm = Pattern.compile("\"([^\"]+)\"").matcher(m.group(3));
            while (cm.find()) {
                cols.add(cm.group(1));
            }
            out.put(m.group(1), new Resource(m.group(1), m.group(2), cols));
        }
        return out;
    }

    /**
     * 收集「数据库真实列」——从所有迁移脚本里的 CREATE TABLE / ADD COLUMN 提取。
     *
     * <p>之所以从迁移脚本推断而非连数据库：CI 环境没有数据库，
     * 且迁移脚本本身就是 schema 的权威来源。
     */
    private static Map<String, Set<String>> parseSchema(Path migrationDir) throws IOException {
        Map<String, Set<String>> schema = new LinkedHashMap<>();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(migrationDir)) {
            s.filter(f -> f.getFileName().toString().endsWith(".sql")).sorted().forEach(files::add);
        }

        Pattern create = Pattern.compile(
                "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?(\\w+)`?\\s*\\((.*?)\\)\\s*ENGINE",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        // 关键：多条 ADD COLUMN 常写成
        //   ALTER TABLE t
        //       ADD COLUMN a INT ...,
        //       ADD COLUMN b INT ...;
        // 因此按「整条 ALTER 语句」抓取，再在其中逐列提取。
        Pattern alterStmt = Pattern.compile(
                "ALTER\\s+TABLE\\s+`?(\\w+)`?\\s+(.*?);",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Pattern addCol = Pattern.compile(
                "ADD\\s+COLUMN\\s+`?(\\w+)`?",
                Pattern.CASE_INSENSITIVE);

        for (Path f : files) {
            String sql = Files.readString(f, StandardCharsets.UTF_8);

            // 1) CREATE TABLE 中的列
            Matcher cm = create.matcher(sql);
            while (cm.find()) {
                String table = cm.group(1);
                Set<String> cols = schema.computeIfAbsent(table, k -> new LinkedHashSet<>());
                for (String line : cm.group(2).split("\\n")) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("--")
                            || t.startsWith("PRIMARY") || t.startsWith("UNIQUE")
                            || t.startsWith("KEY") || t.startsWith("INDEX")
                            || t.startsWith("CONSTRAINT") || t.startsWith("FOREIGN")) {
                        continue;
                    }
                    Matcher lm = Pattern.compile("^`?(\\w+)`?\\s+").matcher(t);
                    if (lm.find()) {
                        cols.add(lm.group(1));
                    }
                }
            }

            // 2) ALTER TABLE ... ADD COLUMN（含多列）
            Matcher am = alterStmt.matcher(sql);
            while (am.find()) {
                String table = am.group(1);
                Set<String> cols = schema.computeIfAbsent(table, k -> new LinkedHashSet<>());
                Matcher colm = addCol.matcher(am.group(2));
                while (colm.find()) {
                    cols.add(colm.group(1));
                }
            }
        }
        return schema;
    }

    private static String camelToSnake(String n) {
        return n.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    /** 从 Vue 文件提取 formFields 中的 key */
    private static List<String> extractFormKeys(String vue) {
        Matcher fm = Pattern.compile("const\\s+formFields[^=]*=\\s*\\[(.*?)\\n\\];", Pattern.DOTALL).matcher(vue);
        if (!fm.find()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        Matcher km = Pattern.compile("key:\\s*['\"](\\w+)['\"]").matcher(fm.group(1));
        while (km.find()) {
            keys.add(km.group(1));
        }
        return keys;
    }

    /**
     * 提取 onSubmit 中**实际提交给通用 CRUD 的字段**。
     *
     * <p><b>为什么不能直接用 formFields</b>：部分页面（如平台主体）表单里既有
     * biz_subject 的字段，也有 platform_profile 的字段，提交时按归属拆成两组分别写入。
     * 若拿「表单全字段」去比对白名单，会把这些**正确地走了另一条路径**的字段误判为丢失。
     *
     * <p>判定顺序：
     * <ol>
     *   <li>优先：若 onSubmit 内有 {@code const xxxPayload = {...}} 形式，取其字段；</li>
     *   <li>退路：若为 {@code {...data}} 整体展开，则回退到 formFields（此时确实应全部落库）。</li>
     * </ol>
     *
     * @return 实际写入的字段名集合；返回空集表示无法判定（跳过校验）
     */
    private static Set<String> payloadKeysFor(String vue, String resource) {
        Matcher om = Pattern.compile(
                "onSubmit:\\s*async\\s*\\(data,\\s*editing\\)\\s*=>\\s*\\{(.*?)\\n    \\}",
                Pattern.DOTALL).matcher(vue);
        if (!om.find()) {
            return Set.of();
        }
        String body = om.group(1);

        Pattern callPattern = Pattern.compile(
                "store\\.(?:add|update)\\(\\s*['\"]" + Pattern.quote(resource) + "['\"][^;]*",
                Pattern.DOTALL);
        Matcher cm = callPattern.matcher(body);
        Set<String> explicitVar = new LinkedHashSet<>();
        boolean spreadData = false;
        while (cm.find()) {
            String call = cm.group();
            if (call.contains("...data")) {
                spreadData = true;
                continue;
            }
            Matcher vm = Pattern.compile(
                    "store\\.(?:add|update)\\(\\s*['\"][^'\"]+['\"]\\s*,\\s*(?:[^,]+,\\s*)?([A-Za-z_$][\\w$]*)")
                    .matcher(call);
            if (vm.find()) {
                explicitVar.add(vm.group(1));
            }
        }

        if (spreadData && explicitVar.isEmpty()) {
            return Set.of("__SPREAD_DATA__");
        }

        Set<String> keys = new LinkedHashSet<>();
        boolean payloadSpreadsData = false;
        for (String varName : explicitVar) {
            Matcher pm = Pattern.compile(
                    "const\\s+" + Pattern.quote(varName) + "\\s*=\\s*\\{(.*?)\\n\\s*\\};",
                    Pattern.DOTALL).matcher(body);
            if (pm.find()) {
                String obj = pm.group(1);
                // 若 payload 内含 ...data，说明表单字段全部会被提交，
                // 此时必须连 formFields 一起校验（否则会漏掉漂移字段）。
                if (obj.contains("...data")) {
                    payloadSpreadsData = true;
                }
                // 只取「顶层 key」：行首的 标识符: ，避免把模板串里的
                // {s.slice(0, 10)} 00:00:00 之类内容误当成字段名。
                for (String line : obj.split("\n")) {
                    Matcher km = Pattern.compile("^\\s{0,8}([A-Za-z_$][\\w$]*)\\s*:").matcher(line);
                    if (km.find()) {
                        keys.add(km.group(1));
                    }
                }
            }
        }
        if (payloadSpreadsData) {
            return Set.of("__SPREAD_DATA__");
        }
        return keys;
    }

    /**
     * 判断该页 onSubmit 是否真的调用 store.add/update/patch('资源', ...)（即走通用 CRUD）。
     *
     * <p><b>为什么只扫 onSubmit</b>：rowActions 里的「启用/停用」也会调用
     * {@code store.patch('subjects', ...)}，但那写的是 {status} 这类固定小对象，
     * 与表单字段无关。若扫描整个文件，会把走专用接口的页面（如渠道管理）
     * 误判为「表单字段会丢失」。
     */
    private static Set<String> genericResources(String vue) {
        Matcher om = Pattern.compile(
                "onSubmit:\\s*async\\s*\\(data,\\s*editing\\)\\s*=>\\s*\\{(.*?)\\n    \\}",
                Pattern.DOTALL).matcher(vue);
        if (!om.find()) {
            return Set.of();
        }
        Set<String> res = new LinkedHashSet<>();
        Matcher m = Pattern.compile("store\\.(?:add|update|patch)\\(\\s*['\"](\\w+)['\"]").matcher(om.group(1));
        while (m.find()) {
            res.add(m.group(1));
        }
        return res;
    }

    @Test
    void formFieldsMustMatchRegistryWhitelist() throws IOException {
        Path root = repoRoot();
        Path registryFile = root.resolve("server/src/main/java/com/wuling/system/crud/CrudRegistry.java");
        Path viewsDir = root.resolve("src/views");
        Path migrationDir = root.resolve("server/src/main/resources/db/migration");

        assertTrue(Files.exists(registryFile), "找不到 CrudRegistry.java: " + registryFile);
        assertTrue(Files.isDirectory(viewsDir), "找不到前端页面目录: " + viewsDir);

        Map<String, Resource> registry = parseRegistry(registryFile);
        assertTrue(registry.size() > 20, "CrudRegistry 解析异常，仅得到 " + registry.size() + " 个资源");

        List<String> problems = new ArrayList<>();
        List<Path> vueFiles = new ArrayList<>();
        try (Stream<Path> s = Files.walk(viewsDir)) {
            s.filter(f -> f.getFileName().toString().endsWith(".vue")).forEach(vueFiles::add);
        }

        for (Path vf : vueFiles) {
            String vue = Files.readString(vf, StandardCharsets.UTF_8);
            List<String> formKeys = extractFormKeys(vue);
            if (formKeys.isEmpty()) {
                continue;
            }
            Set<String> generic = genericResources(vue);
            if (generic.isEmpty()) {
                // 走专用接口的页面，不参与白名单校验
                continue;
            }
            String rel = root.relativize(vf).toString().replace('\\', '/');
            for (String res : generic) {
                Resource def = registry.get(res);
                if (def == null) {
                    continue;
                }
                // 优先用 onSubmit 中实际提交的 payload 字段；整体展开时回退到 formFields
                Set<String> payloadKeys = payloadKeysFor(vue, res);
                List<String> checkedKeys;
                if (payloadKeys.isEmpty()) {
                    // 无法静态判定（如 payload 由函数生成），退回表单字段
                    checkedKeys = formKeys;
                } else if (payloadKeys.size() == 1 && payloadKeys.contains("__SPREAD_DATA__")) {
                    checkedKeys = formKeys;
                } else {
                    checkedKeys = new ArrayList<>(payloadKeys);
                }
                List<String> dropped = new ArrayList<>();
                for (String k : checkedKeys) {
                    if (!def.writable().contains(camelToSnake(k))) {
                        dropped.add(k);
                    }
                }
                if (!dropped.isEmpty()) {
                    problems.add(String.format(
                            "页面 %s 的资源 %s（表 %s）中，字段 %s 不在 CrudRegistry 白名单内，"
                                    + "提交后会被静默丢弃。请二选一：① 前端改用库中真实列名；"
                                    + "② 若确需新列，先在 V*.sql 中建列再登记白名单。",
                            rel, res, def.table(), dropped));
                }
            }
        }

        assertTrue(problems.isEmpty(),
                "检测到 " + problems.size() + " 处「字段名与数据库列不一致」的隐患：\n\n"
                        + String.join("\n\n", problems));
    }

    /** 白名单中的每一列都必须在迁移脚本里真实存在，防止白名单写错列名 */
    @Test
    void registryColumnsMustExistInMigrations() throws IOException {
        Path root = repoRoot();
        Path registryFile = root.resolve("server/src/main/java/com/wuling/system/crud/CrudRegistry.java");
        Path migrationDir = root.resolve("server/src/main/resources/db/migration");
        if (!Files.isDirectory(migrationDir)) {
            return;
        }

        Map<String, Resource> registry = parseRegistry(registryFile);
        Map<String, Set<String>> schema = parseSchema(migrationDir);

        List<String> problems = new ArrayList<>();
        for (Resource def : registry.values()) {
            Set<String> cols = schema.get(def.table());
            if (cols == null) {
                // 表可能由非迁移脚本维护（如外部系统），跳过
                continue;
            }
            for (String col : def.writable()) {
                if (!cols.contains(col)) {
                    problems.add(String.format("资源 %s 的白名单列 %s 在表 %s 的迁移脚本中不存在",
                            def.name(), col, def.table()));
                }
            }
        }
        assertTrue(problems.isEmpty(),
                "CrudRegistry 白名单与迁移脚本不一致：\n\n" + String.join("\n", problems));
    }
}
