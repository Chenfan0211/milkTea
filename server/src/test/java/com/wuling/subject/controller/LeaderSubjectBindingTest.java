package com.wuling.subject.controller;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 团长主体约束测试。
 *
 * <p>背景：{@code biz_subject.bound_user_id} 上有唯一索引，
 * 而团长设计允许与门店/资源方/投资人等主体共存。
 * 若创建 LEADER 主体时写入 bound_user_id，已绑定门店的用户会被唯一键拒绝并抛 500。
 * 本测试锁住：团长主体不得占用 bound_user_id，用户关联只走 user_role_grant。
 */
class LeaderSubjectBindingTest {

    private String source() throws IOException {
        return Files.readString(Path.of("src/main/java/com/wuling/subject/controller/AdminLeaderController.java"));
    }

    @Test
    void leaderSubjectMustNotOccupyBoundUserId() throws IOException {
        String text = source();
        assertFalse(text.contains("insert into biz_subject (code, name, subject_type, status, bound_user_id)"),
                "LEADER 主体不能写 bound_user_id，否则与已绑定门店的用户冲突");
    }

    @Test
    void leaderUserIsResolvedThroughRoleGrant() throws IOException {
        String text = source();
        assertTrue(text.contains("from user_role_grant g where g.subject_id = s.id"),
                "团长用户应通过 user_role_grant 反查");
    }

    @Test
    void removingLeaderMustNotClearOtherRoleBinding() throws IOException {
        String text = source();
        assertFalse(text.contains("update app_user set bound_subject_id = null, business_role = null"),
                "解除团长不能清空用户的门店/资源方绑定");
    }

    @Test
    void assigningLeaderMustBeIdempotentForExistingUser() throws IOException {
        String text = source();
        assertTrue(text.contains("on duplicate key update"),
                "同一用户重新绑定团长必须使用幂等 upsert，逻辑删除后再次 insert 会触发唯一键冲突");
    }
}
