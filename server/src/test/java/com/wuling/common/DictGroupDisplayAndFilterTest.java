package com.wuling.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据字典页「分组中文展示 + 分组筛选」守卫（2026-10 修复）。
 *
 * <p><b>修复的两个线上问题</b>：
 * <ol>
 *   <li>分组列与搜索下拉直接展示 dict_type 编码（store_type 等），运营读不懂；</li>
 *   <li>分组搜索填了没有过滤效果 —— 根因是 dict_type 既不在 searchable 也不在
 *       filterable 白名单，CrudService 会把条件静默丢弃。</li>
 * </ol>
 *
 * <p><b>为什么用静态断言而不是集成测试</b>：与 FieldWriteConsistencyTest 一致，
 * CI 环境无数据库，而 CrudRegistry / 页面文件本身就是约定的权威来源。
 */
class DictGroupDisplayAndFilterTest {

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("src/views"))) {
                return p;
            }
        }
        throw new IllegalStateException("找不到仓库根目录（含 src/views）：" + cwd);
    }

    private static String read(Path p) throws IOException {
        assertTrue(Files.exists(p), "文件应存在: " + p);
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** 去掉空白，便于跨行匹配 Java/Vue 源码片段。 */
    private static String flat(String s) {
        return s.replaceAll("\\s+", "");
    }

    @Test
    void registryShouldExposeDictTypesAsReadOnlyResource() throws IOException {
        String registry = read(repoRoot().resolve(
                "server/src/main/java/com/wuling/system/crud/CrudRegistry.java"));

        assertTrue(registry.contains("\"dictTypes\""), "应登记 dictTypes 资源以提供分组中文名");
        assertTrue(flat(registry).contains("newResource(\"dictTypes\",\"sys_dict_type\""),
                "dictTypes 必须映射 sys_dict_type（dict_name 所在表）");
        assertTrue(flat(registry).contains("\"dictTypes\",\"sys_dict_type\",List.of(),"),
                "dictTypes 的 writable 必须为空列表 —— 该资源只读，防止前端写库");
    }

    @Test
    void registryShouldMakeDictTypeFilterable() throws IOException {
        String registry = flat(read(repoRoot().resolve(
                "server/src/main/java/com/wuling/system/crud/CrudRegistry.java")));

        // 分组筛选依赖 filterable 放行 dict_type，否则条件被静默丢弃（原 Bug）
        assertTrue(registry.contains("List.of(\"item_code\",\"item_name\"),\"sortasc,idasc\",List.of(\"dict_type\"))"),
                "dictEntries 必须把 dict_type 登记进 filterable，分组筛选才会生效");
    }

    @Test
    void dictPageShouldUseChineseGroupNameAndEqFilter() throws IOException {
        String vue = read(repoRoot().resolve("src/views/system/dict/index.vue"));
        String flatVue = flat(vue);

        assertTrue(vue.contains("groupNameOf"), "分组列必须走 groupNameOf 做编码 -> 中文映射");
        assertTrue(vue.contains("dictName"), "中文名必须取自 sys_dict_type.dictName");
        assertTrue(flatVue.contains("options:groupOptions"), "搜索/表单下拉必须复用 groupOptions");
        assertTrue(flatVue.contains("key:'eq_dictType'"), "分组搜索 key 必须为 eq_dictType（等值过滤）");
        // 只校验 searchFields 区块：表单字段仍应使用 dictType（写库口径是编码，不能改）
        int sfStart = vue.indexOf("searchFields: SearchField[]");
        int sfEnd = vue.indexOf("const formFields");
        assertTrue(sfStart >= 0 && sfEnd > sfStart, "应能在页面中定位 searchFields 区块");
        String searchBlock = flat(vue.substring(sfStart, sfEnd));
        assertFalse(searchBlock.contains("key:'dictType'"),
                "搜索区块不得保留旧的 dictType key（该列不在白名单，条件会被丢弃）");
        assertTrue(flatVue.contains("remoteDeps:['dictTypes']"), "页面必须预加载 dictTypes 后再渲染");
    }

    @Test
    void storeShouldExposeDictTypes() throws IOException {
        String store = flat(read(repoRoot().resolve("src/store/modules/admin/index.ts")));

        assertTrue(store.contains("dictTypes:'dictTypes'"), "REMOTE_RESOURCES 必须登记 dictTypes");
        assertTrue(store.contains("dictTypes:computed(()=>ensure('dictTypes'))"), "store 必须导出 dictTypes 镜像");
    }
}
