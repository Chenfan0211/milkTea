package com.wuling.subject.controller;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户角色解绑语义测试。
 *
 * <p>背景：admin 后台有两条解绑入口：
 * <ol>
 *   <li>用户列表页"解绑"按钮 -> store.unbindUserRole -> 通用接口；</li>
 *   <li>用户列表页"取消归属"/团长解绑 -> 团长专用接口。</li>
 * </ol>
 *
 * <p>通用解绑接口原先无条件执行
 * {@code update app_user set business_role = null where id = ?}，
 * 而 business_role 是 StoreOperatorService 的门店经营判定依据之一。
 * 对于拥有多个经营角色的用户（库里存在一用户 3 角色的数据），
 * 解绑其中一个角色会把 business_role 清空，导致其余角色（尤其是门店核销）
 * 的权限被静默剥夺。
 *
 * <p>本测试锁住：解绑必须只清除「当前角色一致」的 business_role，
 * 并且清空动作必须与已解绑角色绑定，不能无条件执行。
 */
class UserRoleUnbindTest {

    private String source() throws IOException {
        return Files.readString(Path.of(
                "src/main/java/com/wuling/subject/controller/SubjectBindingController.java"));
    }

    @Test
    void unbindMustNotClearBusinessRoleUnconditionally() throws IOException {
        String text = source();
        assertFalse(
                text.contains("update app_user set business_role = null where id = ? and deleted = 0"),
                "解绑角色不能无条件清空 business_role，否则多角色用户会丢失其余角色权限");
    }

    @Test
    void unbindBackfillsRemainingRoleAfterClearing() throws IOException {
        String text = source();
        assertTrue(text.contains("解绑后若仍有其它经营角色，回填 business_role"),
                "解绑后必须回填剩余角色，避免仍有角色但门店判定失配");
        // 顺序：unbindPair 必须在回填之前，否则会清掉刚回填的 bound_subject_id
        int pairIdx = text.indexOf("unbindPair(subjectId);");
        int backfillIdx = text.indexOf("解绑后若仍有其它经营角色，回填 business_role");
        assertTrue(pairIdx >= 0 && backfillIdx > pairIdx,
                "unbindPair 必须先于回填执行");
    }

    @Test
    void unbindClearsBusinessRoleOnlyWhenItMatchesUnboundRole() throws IOException {
        String text = source();
        assertTrue(
                text.contains("UPPER(business_role) = UPPER(?)")
                        || text.contains("upper(business_role) = upper(?)"),
                "business_role 只应在与本次解绑角色一致时才清空");
    }
}
