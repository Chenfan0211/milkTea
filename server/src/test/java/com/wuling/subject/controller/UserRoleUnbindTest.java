package com.wuling.subject.controller;

import com.wuling.subject.entity.BizSubject;
import com.wuling.subject.mapper.BizSubjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用户角色解绑/重绑语义测试。
 *
 * <p>背景一：通用解绑接口原先无条件清空 app_user.business_role，
 * 而该字段是 StoreOperatorService 的门店经营判定依据，
 * 导致多角色用户解绑任一角色后静默丢失门店核销权限。
 *
 * <p>背景二：解绑后无法重新绑定同一角色 —— bindPair 只校验
 * app_user.bound_subject_id，多角色用户该字段仍指向主主体，
 * 于是重绑被「已绑定其他主体」误拦。
 */
class UserRoleUnbindTest {

    private static final String SOURCE =
            "src/main/java/com/wuling/subject/controller/SubjectBindingController.java";

    private JdbcTemplate jdbcTemplate;
    private BizSubjectMapper bizSubjectMapper;
    private SubjectBindingController controller;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        bizSubjectMapper = mock(BizSubjectMapper.class);
        controller = new SubjectBindingController(bizSubjectMapper, null, jdbcTemplate);
    }

    private String source() throws IOException {
        return Files.readString(Path.of(SOURCE));
    }

    @Test
    @DisplayName("解绑角色不能无条件清空 business_role")
    void unbindMustNotClearBusinessRoleUnconditionally() throws IOException {
        String text = source();
        assertFalse(text.contains("update app_user set business_role = null where id = ? and deleted = 0"),
                "无条件清空 business_role 会让多角色用户丢失其余角色权限");
    }

    @Test
    @DisplayName("business_role 只在与解绑角色一致时才清空")
    void unbindClearsBusinessRoleOnlyWhenItMatchesUnboundRole() throws IOException {
        String text = source();
        assertTrue(text.contains("UPPER(business_role) = UPPER(?)"),
                "business_role 只应在与本次解绑角色一致时清空");
    }

    @Test
    @DisplayName("解绑后回填剩余角色，且顺序在 unbindPair 之后")
    void unbindBackfillsRemainingRoleAfterUnbindPair() throws IOException {
        String text = source();
        int pairIdx = text.indexOf("unbindPair(subjectId);");
        int backfillIdx = text.indexOf("解绑后若仍有其它经营角色");
        assertTrue(pairIdx >= 0 && backfillIdx > pairIdx,
                "必须先解绑再回填，否则回填的 bound_subject_id 会被 unbindPair 清掉");
    }

    @Test
    @DisplayName("多角色用户重新绑定非主角色不被「已绑定其他主体」拦截")
    void rebindRoleForMultiRoleUserIsAllowed() {
        long userId = 1L;
        long subjectId = 301L;
        long mainSubjectId = 101L;

        BizSubject subject = new BizSubject();
        subject.setId(subjectId);
        subject.setSubjectType("CHANNEL");
        when(bizSubjectMapper.selectById(subjectId)).thenReturn(subject);

        // 该角色尚未授予
        when(jdbcTemplate.queryForObject(contains("from user_role_grant"), eq(Long.class), any(Object[].class)))
                .thenReturn(0L);
        // 用户主主体指向 STORE(101)，多角色常态
        when(jdbcTemplate.queryForList(contains("bound_subject_id from app_user"), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of(mainSubjectId));
        // 目标主体未被其他用户占用
        when(jdbcTemplate.queryForList(contains("bound_user_id from biz_subject"), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of());

        assertDoesNotThrow(() -> controller.bindUserRole(userId, "CHANNEL", subjectId),
                "多角色用户重绑非主角色时不应被 1:1 校验拦截");
    }

    @Test
    @DisplayName("主体已被其他用户占用时仍然拒绝（保留 1:1 约束）")
    void rebindRejectsWhenSubjectBoundToAnotherUser() {
        long userId = 1L;
        long subjectId = 301L;

        BizSubject subject = new BizSubject();
        subject.setId(subjectId);
        subject.setSubjectType("CHANNEL");
        when(bizSubjectMapper.selectById(subjectId)).thenReturn(subject);

        when(jdbcTemplate.queryForObject(contains("from user_role_grant"), eq(Long.class), any(Object[].class)))
                .thenReturn(0L);
        when(jdbcTemplate.queryForList(contains("bound_subject_id from app_user"), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of(101L));
        // 主体已绑给用户 99
        when(jdbcTemplate.queryForList(contains("bound_user_id from biz_subject"), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of(99L));

        org.junit.jupiter.api.Assertions.assertThrows(
                com.wuling.common.exception.BusinessException.class,
                () -> controller.bindUserRole(userId, "CHANNEL", subjectId),
                "主体已绑其他用户时必须拒绝，避免一店多绑");
    }
}
