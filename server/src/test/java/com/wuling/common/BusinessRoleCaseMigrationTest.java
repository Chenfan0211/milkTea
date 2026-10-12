package com.wuling.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V72「经营角色大小写归一」迁移守卫 + 审核/鉴权路径大小写口径一致性。
 *
 * <p><b>背景（2026-10 经营角色链路排查）</b>：审核路径
 * {@code SubjectBindingController#reviewApplication} 历史上用 {@code toLowerCase()}
 * 写 {@code app_user.business_role} 与 {@code user_role_grant.role_code}，
 * 而 {@code bindUserRole}（normalizeRoleCode）、V36、AppRoleController#mine、
 * InternalQueryController#storeOperatorCheck 都用大写。小写存量导致
 * 「已开通门店经营者却读不到角色 / 选品核销 403」。
 *
 * <p>本测试保证：
 * <ol>
 *   <li>V72 迁移覆盖小写/中文/资源方别名并只归并到权威大写枚举；</li>
 *   <li>审核路径写入角色已改用权威大写（而非 toLowerCase）；</li>
 *   <li>鉴权路径读 business_role 已用 UPPER 归一，兼容小写存量。</li>
 * </ol>
 */
class BusinessRoleCaseMigrationTest {

    private static final Path MIGRATION =
            Paths.get("src/main/resources/db/migration/V72__normalize_business_role_case.sql");

    private static final Path REVIEW_CTRL =
            Paths.get("src/main/java/com/wuling/subject/controller/SubjectBindingController.java");

    private static final Path OPERATOR_CHECK =
            Paths.get("src/main/java/com/wuling/common/internal/InternalQueryController.java");

    private static final Set<String> CANONICAL = Set.of("STORE", "INVESTOR", "CHANNEL");

    private String read(Path p) throws IOException {
        assertTrue(Files.exists(p), "文件应存在: " + p);
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    void migrationShouldNormalizeBothTablesToCanonicalCase() throws IOException {
        String sql = read(MIGRATION);
        assertTrue(sql.contains("UPDATE app_user"), "应归并 app_user.business_role");
        assertTrue(sql.contains("UPDATE user_role_grant"), "应归并 user_role_grant.role_code");
        // 权威大写目标必须出现
        for (String c : CANONICAL) {
            assertTrue(sql.contains("'" + c + "'"), "迁移必须归并到权威值 " + c);
        }
        // 资源方别名 RESOURCE -> CHANNEL 必须保留
        assertTrue(sql.contains("'RESOURCE'"), "必须覆盖资源方别名 RESOURCE");
        // 幂等：纯 UPDATE，无 DDL，无伪造历史
        String lower = sql.toLowerCase();
        assertFalse(lower.contains("alter table"), "V72 不应包含 DDL");
        assertFalse(lower.contains("create table"), "V72 不应包含 DDL");
        assertFalse(lower.contains("flyway_schema_history"), "严禁伪造 flyway 历史");
    }

    @Test
    void reviewApplicationShouldWriteCanonicalCase() throws IOException {
        String ctrl = read(REVIEW_CTRL);
        // 审核路径必须把角色归一为权威大写再写库，不得直接写 toLowerCase 结果
        assertTrue(ctrl.contains("normalizeRoleCode(roleType)"),
                "审核路径必须用 normalizeRoleCode 归一角色为权威大写");
        assertTrue(ctrl.contains("canonicalRole"),
                "审核路径写入 user_role_grant / app_user 必须使用归一后的 canonicalRole");
    }

    @Test
    void operatorCheckShouldReadCaseInsensitively() throws IOException {
        String ctrl = read(OPERATOR_CHECK);
        assertTrue(ctrl.contains("UPPER(business_role) = 'STORE'"),
                "鉴权路径读 business_role 必须 UPPER 归一，兼容历史小写存量");
    }

    @Test
    void investorLookupShouldReadCaseInsensitively() throws IOException {
        String ctrl = read(REVIEW_CTRL);
        assertTrue(ctrl.contains("UPPER(role_code) = 'INVESTOR'"),
                "投资人授权查询 role_code 必须 UPPER 归一，兼容历史小写存量");
        assertTrue(ctrl.contains("UPPER(business_role) = 'INVESTOR'"),
                "投资人回退查询 business_role 必须 UPPER 归一，兼容历史小写存量");
    }
}
