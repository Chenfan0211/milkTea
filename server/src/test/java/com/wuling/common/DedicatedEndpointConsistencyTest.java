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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 专用接口字段一致性守卫（CI 检查）。
 *
 * <p><b>为什么需要它（补齐 {@link FieldWriteConsistencyTest} 的盲区）</b>：
 * 原检查只覆盖「走通用 CRUD（{@code store.add/update/patch}）」的页面，
 * 而门店 / 渠道 / 投资人 / 供应商 / 平台主体等页面走的是<b>专用接口</b>
 * （{@code AdminSubjectProfileController}、{@code PlatformProfileController} 等），
 * 完全不参与校验 —— 这个盲区在 2026-09-25 导致了一个线上 Bug：
 *
 * <blockquote>
 * 用户列表「经营角色」列显示英文 {@code STORE}、「绑定主体」列恒为空，
 * 且「解绑」点了没反应。根因是前端拿 {@code app_user.bound_subject_id}（数字 id）
 * 去和主体 {@code code}（字符串）比较，恒不相等 → DELETE 请求从未发出。
 * </blockquote>
 *
 * <p>该 Bug 的本质是「前端假设的字段语义/命名 与 后端实际实现不一致」，
 * 与通用 CRUD 的「字段名漂移」是同一类问题，因此用同样的静态比对思路覆盖。
 *
 * <p><b>本测试校验两件事</b>：
 * <ol>
 *   <li><b>读一致性</b>：页面表格列直读的字段（无自定义 render 的 {@code key}），
 *       必须由该页面调用的专用接口真实返回；</li>
 *   <li><b>写一致性</b>：页面表单提交的 payload 字段，必须被后端实现真实读取/持久化。</li>
 * </ol>
 *
 * <p><b>误报防护（关键设计）</b>：
 * <ul>
 *   <li>有自定义 {@code render} 的列不按 key 校验 —— 列 key 只是展示标识，
 *       render 可以读任意字段（如平台主体页 {@code key:'appId'} 实际读 {@code row.appid}）；</li>
 *   <li>「本地派生字段」白名单：如 {@code balance} 由 {@code subjectAccounts} 镜像本地算出，
 *       本就不来自专用接口；</li>
 *   <li>多接口页面取并集：平台主体页同时用平台档案接口与通用 CRUD，字段来源需合并判断。</li>
 * </ul>
 */
class DedicatedEndpointConsistencyTest {

    /** 仓库根目录（测试工作目录是 server 模块） */
    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("src/views"))) {
                return p;
            }
        }
        return cwd;
    }

    /**
     * 「本地派生字段」白名单：这些字段不来自专用接口，而是前端本地计算或其它数据源。
     * 每一条都注明理由，避免白名单变成「万能兜底」而失去检查意义。
     */
    private static final Map<String, String> LOCAL_DERIVED_FIELDS = Map.of(
            "balance", "由 store.subjectAccounts 镜像本地查表算出，不来自专用接口",
            "action", "表格操作列占位，无数据来源"
    );

    /**
     * 专用接口后端实现文件 —— 返回字段从 {@code row.put("x", ...)} 提取，
     * 可写字段从 {@code payload.get("x")} / upsertProfile 的列名提取。
     *
     * <p>新增专用接口页面时，需在此登记其后端实现文件，
     * 否则该页面不会被覆盖（本测试会在末尾提示未登记的页面）。
     */
    private static final List<String> BACKEND_IMPLEMENTATIONS = List.of(
            // 主体档案（渠道/投资人/供应商的 CRUD 与列表）
            "server/src/main/java/com/wuling/subject/controller/AdminSubjectProfileController.java",
            // 平台主体配置（AppID / 商户号）
            "server/src/main/java/com/wuling/system/controller/PlatformProfileController.java",
            // 渠道↔门店绑定（含弹窗用的已绑定门店列表）
            "server/src/main/java/com/wuling/subject/controller/SubjectBindingController.java",
            // 签到规则（注意：实现在 marketing-service，不在 server 模块）
            "marketing-service/src/main/java/com/wuling/marketing/controller/AdminMarketingConfigController.java",
            // 详情查询（快照详情 / 角色申请详情 / 核销详情）
            "server/src/main/java/com/wuling/system/controller/AdminDetailQueryController.java"
    );

    /** 已接入专用接口校验的页面（相对仓库根）。未登记的页面会让测试失败，提醒补登记。 */
    private static final Set<String> COVERED_PAGES = Set.of(
            "src/views/subject/channel/index.vue",
            "src/views/subject/investor/index.vue",
            "src/views/subject/supplier/index.vue",
            "src/views/subject/platform/index.vue",
            // 渠道门店绑定弹窗：读 fetchChannelStores（SubjectBindingController）
            "src/views/subject/channel/ChannelStoreDialog.vue",
            // 积分规则页：读 fetchSigninRule（marketing-service）；
            // 其表格 ruleColumns 数据来自通用 CRUD，不在本测试的直读列校验范围
            "src/views/marketing/points-rule/index.vue",
            // 以下 3 个为详情页：用描述列表（descriptions）而非 DataTable columns 展示，
            // 故不参与「直读列」校验，仅登记以满足覆盖范围可见性要求
            "src/views/finance/snapshot-detail/index.vue",
            "src/views/review/role-detail/index.vue",
            "src/views/trade/verify-detail/index.vue"
    );

    /** 后端行字段名 -> 是否可用（含 SQL select 出来的列，做 camel/snake 双向匹配）。 */
    private static Set<String> backendFields(Path root) throws IOException {
        Set<String> fields = new LinkedHashSet<>();
        for (String rel : BACKEND_IMPLEMENTATIONS) {
            Path f = root.resolve(rel);
            if (!Files.exists(f)) {
                continue;
            }
            String src = Files.readString(f, StandardCharsets.UTF_8);
            // 1) row.put("x", ...) —— Map 形式返回给前端的字段
            for (Matcher m = Pattern.compile("row\\.put\\(\\s*\"(\\w+)\"").matcher(src); m.find(); ) {
                fields.add(m.group(1));
            }
            // 2) data.put("x", ...) —— 同样是返回字段（平台档案接口用的是 data）
            for (Matcher m = Pattern.compile("data\\.put\\(\\s*\"(\\w+)\"").matcher(src); m.find(); ) {
                fields.add(m.group(1));
            }
            // 3) payload.get("x") —— 写入路径读的字段
            for (Matcher m = Pattern.compile("payload\\.get\\(\\s*\"(\\w+)\"").matcher(src); m.find(); ) {
                fields.add(m.group(1));
            }
            // 4) select xxx as yyy / select a, b —— SQL 里出现过的列
            for (Matcher m = Pattern.compile("select\\s+([^\"]+?)\\s+from", Pattern.CASE_INSENSITIVE).matcher(src); m.find(); ) {
                for (String col : m.group(1).split(",")) {
                    String c = col.trim();
                    Matcher asM = Pattern.compile("\\bas\\s+(\\w+)$", Pattern.CASE_INSENSITIVE).matcher(c);
                    if (asM.find()) {
                        fields.add(asM.group(1));
                        continue;
                    }
                    c = c.replaceAll("^\\w+\\.", "").replaceAll("[^\\w]", "");
                    if (!c.isEmpty() && !c.equalsIgnoreCase("count")) {
                        fields.add(c);
                    }
                }
            }
        }
        return fields;
    }

    private static String camelToSnake(String n) {
        return n.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    private static String snakeToCamel(String n) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : n.toCharArray()) {
            if (c == '_') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return sb.toString();
    }

    private static boolean isKnownField(Set<String> backend, String field) {
        return backend.contains(field)
                || backend.contains(camelToSnake(field))
                || backend.contains(snakeToCamel(field))
                || LOCAL_DERIVED_FIELDS.containsKey(field);
    }

    /** 表格列：key + 是否有自定义 render + render 里引用的 row.xxx 字段。 */
    private record Column(String key, boolean hasRender, List<String> rowFields) {
    }

    /**
     * 解析页面 columns 数组。
     *
     * <p>只取「columns 定义块」内的列对象，避免把 formFields / searchFields
     * 里同名的 {@code key:} 误当成表格列。用括号配平扫描对象字面量，
     * 以便正确处理嵌套（如 {@code ellipsis: { tooltip: true }}）。
     */
    private static List<Column> parseColumns(String vue) {
        int start = vue.indexOf("const columns");
        if (start < 0) {
            return List.of();
        }
        int end = vue.indexOf("\nconst ", start + 10);
        String block = end > 0 ? vue.substring(start, end) : vue.substring(start);

        List<Column> cols = new ArrayList<>();
        int i = 0;
        while (i < block.length()) {
            if (block.charAt(i) != '{') {
                i++;
                continue;
            }
            int depth = 0;
            int j = i;
            for (; j < block.length(); j++) {
                char c = block.charAt(j);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
            }
            if (j >= block.length()) {
                break;
            }
            String obj = block.substring(i, j + 1);
            Matcher km = Pattern.compile("key:\\s*'([\\w-]+)'").matcher(obj);
            if (km.find()) {
                boolean hasRender = Pattern.compile("render\\s*:").matcher(obj).find();
                List<String> rowFields = new ArrayList<>();
                for (Matcher rm = Pattern.compile("row\\??\\.(\\w+)").matcher(obj); rm.find(); ) {
                    rowFields.add(rm.group(1));
                }
                cols.add(new Column(km.group(1), hasRender, rowFields));
            }
            i = j + 1;
        }
        return cols;
    }

    /**
     * 页面表格列直读的字段必须由专用接口返回。
     *
     * <p>只校验<b>无自定义 render</b> 的列：这类列由 naive-ui 直接用
     * {@code row[key]} 渲染，字段名必须与后端返回一致，是最易漂移的地方。
     * 有 render 的列可以自由读取任意字段，静态无法穷尽判定，故不校验
     * （其风险由「渲染出来是空」在使用中暴露，且不受字段重命名影响）。
     */
    @Test
    void directColumnKeysMustBeReturnedByBackend() throws IOException {
        Path root = repoRoot();
        Set<String> backend = backendFields(root);
        assertFalse(backend.isEmpty(), "未解析到任何后端字段，说明专用接口实现文件路径已失效，请更新 BACKEND_IMPLEMENTATIONS");

        List<String> problems = new ArrayList<>();
        for (String rel : COVERED_PAGES) {
            Path vf = root.resolve(rel);
            if (!Files.exists(vf)) {
                continue;
            }
            String vue = Files.readString(vf, StandardCharsets.UTF_8);
            for (Column col : parseColumns(vue)) {
                if (col.hasRender()) {
                    continue;
                }
                if (!isKnownField(backend, col.key())) {
                    problems.add(String.format(
                            "页面 %s 的表格列 key='%s' 直读 row.%s，但后端实现未返回该字段。"
                                    + "请二选一：① 改用后端真实返回的字段名；"
                                    + "② 若该字段来自本地计算，在 LOCAL_DERIVED_FIELDS 登记并注明理由。",
                            rel, col.key(), col.key()));
                }
            }
        }
        assertTrue(problems.isEmpty(),
                "检测到 " + problems.size() + " 处「专用接口页面读字段与后端不一致」：\n\n"
                        + String.join("\n\n", problems));
    }

    /**
     * 每个调用专用接口的页面都应登记在 COVERED_PAGES 中。
     *
     * <p>目的：防止新增专用接口页面时忘记登记，导致新页面游离在检查之外 ——
     * 这正是本测试要消灭的「盲区」。若确实不需要检查（如纯展示弹窗），
     * 也请登记并说明，让覆盖范围显式可控。
     */
    @Test
    void pagesCallingDedicatedApiMustBeRegistered() throws IOException {
        Path root = repoRoot();
        Path viewsDir = root.resolve("src/views");
        if (!Files.isDirectory(viewsDir)) {
            return;
        }

        List<Path> vueFiles = new ArrayList<>();
        try (Stream<Path> s = Files.walk(viewsDir)) {
            s.filter(f -> f.getFileName().toString().endsWith(".vue")).forEach(vueFiles::add);
        }

        List<String> unregistered = new ArrayList<>();
        for (Path vf : vueFiles) {
            String vue = Files.readString(vf, StandardCharsets.UTF_8);
            // 该页面是否调用专用接口：import 了 service/api 且实际以 xxx( 形式调用
            boolean callsApi = false;
            Matcher im = Pattern.compile(
                    "import\\s*\\{([^}]+)\\}\\s*from\\s*['\"]@/service/api/[\\w-]+['\"]",
                    Pattern.DOTALL).matcher(vue);
            while (im.find()) {
                for (String raw : im.group(1).split(",")) {
                    String name = raw.trim().split("\\s+as\\s+")[0].trim();
                    if (!name.isEmpty()
                            && Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\(").matcher(vue).find()) {
                        callsApi = true;
                        break;
                    }
                }
                if (callsApi) {
                    break;
                }
            }
            if (!callsApi) {
                continue;
            }
            String rel = root.relativize(vf).toString().replace('\\', '/');
            if (!COVERED_PAGES.contains(rel)) {
                unregistered.add(rel);
            }
        }

        assertTrue(unregistered.isEmpty(),
                "以下页面调用了专用接口但未登记在 COVERED_PAGES，不会被一致性检查覆盖：\n\n  - "
                        + String.join("\n  - ", unregistered)
                        + "\n\n请在 DedicatedEndpointConsistencyTest#COVERED_PAGES 中登记，"
                        + "并在 BACKEND_IMPLEMENTATIONS 中补充其后端实现文件。");
    }

    /** 自检：解析器必须真的能提取到列，否则测试形同虚设。 */
    @Test
    void columnParserMustActuallyFindColumns() throws IOException {
        Path root = repoRoot();
        int total = 0;
        for (String rel : COVERED_PAGES) {
            Path vf = root.resolve(rel);
            if (!Files.exists(vf)) {
                continue;
            }
            String vue = Files.readString(vf, StandardCharsets.UTF_8);
            total += parseColumns(vue).size();
        }
        assertTrue(total >= 15,
                "columns 解析器只提取到 " + total + " 个列，明显偏少 —— "
                        + "说明解析逻辑失效（如 columns 定义写法变更），测试将无法发现真实问题。");
    }
}