package com.wuling.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行操作「异步写库后刷新」守卫（CI 检查）。
 *
 * <p><b>为什么需要它（真实线上 Bug）</b>：2026-09-25 用户反馈
 * 「绑定后没自动刷新页面」—— 用户列表点「绑定」选完主体，列表不更新，手动刷新才出现。
 *
 * <p>根因在 {@code AdminListPage.vue} 的 {@code confirmCascadePicker}：
 * <pre>
 * // ❌ 错误：没 await 就直接刷新
 * pendingCascadeAction.value?.cascadePicker?.handler?.(row, {...});
 * loadData();          // 写库还没完成就查询 → 读到旧数据
 *
 * // ✅ 正确：先 await 写库，再刷新
 * await pendingCascadeAction.value?.cascadePicker?.handler?.(row, {...});
 * await loadData();
 * </pre>
 *
 * <p>对照证据：同一文件里 {@code confirmPicker}（解绑走这里）与 {@code confirmReason}
 * 一直都有 {@code await}，所以「解绑正常、绑定失效」。这说明问题是
 * <b>遗漏</b>而非设计缺陷 —— 靠人工 review 极易漏看，故用静态检查兜住。
 *
 * <p>检查规则：所有形如 {@code xxx?.handler?.(...)} 的调用，若其后紧邻 {@code loadData()}
 * 刷新，则该 handler 调用必须有 {@code await}。
 */
class AsyncActionAwaitTest {

    private static final Path LIST_PAGE =
            Paths.get("../src/views/_shared/AdminListPage.vue");

    private static Path resolveListPage() {
        // 测试工作目录是 server 模块；兼容从仓库根运行的情况
        Path[] candidates = {
                Paths.get("../src/views/_shared/AdminListPage.vue"),
                Paths.get("src/views/_shared/AdminListPage.vue")
        };
        for (Path p : candidates) {
            if (Files.exists(p)) {
                return p;
            }
        }
        return LIST_PAGE;
    }

    /**
     * 每个 handler 调用点都必须有 await（其后若跟 loadData 刷新）。
     *
     * <p>实现：逐行扫描，遇到含 {@code handler?.(} 的行，检查该行是否以 await 开头；
     * 未 await 即记录问题。
     */
    @Test
    void asyncHandlerCallsMustBeAwaited() throws IOException {
        Path file = resolveListPage();
        assertTrue(Files.exists(file), "找不到 " + file);

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<String> problems = new ArrayList<>();

        Pattern handlerCall = Pattern.compile("handler\\?\\.\\(");
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!handlerCall.matcher(line).find()) {
                continue;
            }
            String trimmed = line.trim();
            // 注释行不参与判定
            if (trimmed.startsWith("*") || trimmed.startsWith("//")) {
                continue;
            }
            if (!trimmed.startsWith("await")) {
                problems.add(String.format(
                        "AdminListPage.vue 第 %d 行：handler 调用未 await —— 若该 handler 是异步写库，"
                                + "紧随其后的 loadData() 会早于写库完成，页面读到旧数据（绑定不刷新即此因）。%n    实际代码：%s",
                        i + 1, trimmed));
            }
        }

        assertTrue(problems.isEmpty(),
                "检测到 " + problems.size() + " 处「异步 handler 未 await」：\n\n"
                        + String.join("\n\n", problems));
    }

    /**
     * 自检：必须真的能在文件里找到 handler 调用点，否则测试形同虚设
     * （例如写法规避了正则，检查会静默通过却毫无保护作用）。
     */
    @Test
    void scannerMustActuallyFindHandlerCalls() throws IOException {
        Path file = resolveListPage();
        String src = Files.readString(file, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("handler\\?\\.").matcher(src);
        int count = 0;
        while (m.find()) {
            count++;
        }
        assertTrue(count >= 4,
                "只扫描到 " + count + " 个 handler 调用点，明显偏少 —— "
                        + "说明写法已变更（如改用别的方式调用 handler），本守卫会失去作用，请同步更新扫描规则。");
    }

    /**
     * 关键 handler 确认已改为 async 并 await（防止有人改回去）。
     *
     * <p>级联选择是本次 Bug 的直接现场，单独钉住。
     */
    @Test
    void cascadePickerConfirmMustAwaitHandler() throws IOException {
        Path file = resolveListPage();
        String src = Files.readString(file, StandardCharsets.UTF_8);

        assertTrue(src.contains("async function confirmCascadePicker"),
                "confirmCascadePicker 应为 async —— 否则无法 await 异步绑定 handler");

        int start = src.indexOf("async function confirmCascadePicker");
        int end = src.indexOf("\n}", start);
        String body = src.substring(start, end > 0 ? end : src.length());

        assertTrue(body.contains("await pendingCascadeAction"),
                "confirmCascadePicker 内必须 await 级联 handler，否则写库未完成就刷新（原 Bug）");
        assertTrue(body.contains("await loadData"),
                "confirmCascadePicker 内应 await loadData，保证刷新完成后再关弹窗流程");
    }
}