package com.wuling.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V54：评论下线与资金流水增强迁移守卫。 */
class CommentOfflineAndFundFlowMigrationV54Test {

    private static final Path MIGRATION = Paths.get(
            "src/main/resources/db/migration/V54__comments_offline_and_fund_flow_enhancement.sql");

    private String normalizedSql() throws IOException {
        assertTrue(Files.exists(MIGRATION), "迁移脚本应存在: " + MIGRATION);
        return Files.readString(MIGRATION, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .toLowerCase()
                .replaceAll("\\s+", " ");
    }

    @Test
    void shouldAddFundFlowColumnsAndIndexesIdempotently() throws IOException {
        String sql = normalizedSql();

        assertTrue(sql.contains("alter table fund_flow add column account_id bigint unsigned null"),
                "应增加 account_id");
        assertTrue(sql.contains("alter table fund_flow add column order_id bigint unsigned null"),
                "应增加 order_id");
        assertTrue(sql.contains("alter table fund_flow add column settlement_record_id bigint unsigned null"),
                "应增加 settlement_record_id");
        assertTrue(sql.contains("alter table fund_flow add column biz_type varchar(32) null"),
                "应增加 biz_type");
        assertTrue(sql.contains("alter table fund_flow add column biz_no varchar(64) null"),
                "应增加 biz_no");
        assertTrue(sql.contains("alter table fund_flow add column balance_bucket varchar(16) null"),
                "应增加 balance_bucket");
        assertTrue(sql.contains("alter table fund_flow add column balance_before bigint null"),
                "应增加 balance_before（分为单位）");
        assertTrue(sql.contains("alter table fund_flow add column change_amount bigint null"),
                "应增加带符号 change_amount（分为单位）");
        assertTrue(sql.contains("alter table fund_flow add column settlement_status varchar(32) null"),
                "应增加 settlement_status");
        assertFalse(sql.contains("alter table fund_flow add column balance_after"),
                "应继续复用已有 balance_after，不得重复新增");

        for (String indexName : new String[]{
                "idx_fund_flow_account",
                "idx_fund_flow_order",
                "idx_fund_flow_biz_no",
                "idx_fund_flow_settlement_record"}) {
            assertTrue(sql.contains("add index " + indexName), "应增加索引: " + indexName);
        }
        assertTrue(sql.contains("from information_schema.columns"), "新增字段必须按 information_schema 判断存在性");
        assertTrue(sql.contains("from information_schema.statistics"), "新增索引必须按 information_schema 判断存在性");
        assertTrue(countOccurrences(sql, "prepare stmt from @sql") >= 14, "DDL 应通过动态 SQL 幂等执行");
        assertTrue(countOccurrences(sql, "deallocate prepare stmt") >= 14, "动态 SQL 应完整释放");
    }

    @Test
    void shouldBackfillOnlyReliableReferencesAndNeverAlterHistoricalBalances() throws IOException {
        String sql = normalizedSql();

        assertTrue(sql.contains("update fund_flow f join subject_account a on a.subject_id = f.subject_id"),
                "account_id 应只按 subject_account.subject_id 唯一关联回填");
        assertTrue(sql.contains("update fund_flow f join orders o on o.order_no = f.order_no"),
                "order_id 应只按唯一 order_no 回填");
        assertTrue(sql.contains("join settlement_record sr on sr.order_id = f.order_id and sr.subject_id = f.subject_id"),
                "settlement_record_id 应按订单 + 主体关联");
        assertTrue(sql.contains("select count(*) from settlement_record sr_count"), "结算记录匹配必须校验唯一性");
        assertTrue(sql.contains(") = 1;"), "结算记录仅在唯一匹配时回填");

        assertFalse(sql.contains("update fund_flow set balance_before"),
                "不得批量猜测历史变动前余额");
        assertFalse(sql.contains("update fund_flow set change_amount"),
                "不得批量猜测历史变动金额");
        assertFalse(sql.contains("update fund_flow set balance_after"),
                "不得批量重写历史变动后余额");
        assertFalse(sql.contains("update fund_flow set settlement_status"),
                "不得批量猜测历史结算状态");
        assertFalse(sql.contains("update subject_account"), "迁移不得批量改动账户余额");
        assertFalse(sql.contains("set available_balance"), "迁移不得改动可用余额");
        assertFalse(sql.contains("set frozen_balance"), "迁移不得改动冻结余额");
    }

    @Test
    void shouldLogicallyRetireCommentMenuWithoutDeletingCommentData() throws IOException {
        String sql = normalizedSql();

        assertTrue(sql.contains("update sys_role_menu rm"), "应先逻辑删除角色菜单关联");
        assertTrue(sql.contains("set rm.deleted = 1"), "角色菜单关联应使用逻辑删除");
        assertTrue(sql.contains("update sys_menu"), "应逻辑删除评论审核菜单");
        assertTrue(sql.contains("set deleted = 1, status = 0"), "菜单应标记删除并停用");
        assertTrue(sql.contains("m.code = 'marketing_comment' or m.path = '/marketing/comment'"),
                "菜单匹配应覆盖 code 与 path");
        assertFalse(sql.contains("delete from marketing_comment"), "不得硬删除评论数据");
        assertFalse(sql.contains("drop table marketing_comment"), "不得删除评论表");
        assertFalse(sql.contains("delete from sys_menu"), "菜单只允许逻辑删除");
    }

    @Test
    void shouldAddRefundNoAndNeverTouchFlywayHistory() throws IOException {
        String sql = normalizedSql();

        assertTrue(sql.contains("alter table reconcile_issue add column refund_no varchar(64) null"),
                "对账异常应增加 refund_no");
        assertTrue(sql.contains("add index idx_reconcile_refund"), "refund_no 应可检索");
        assertFalse(sql.contains("flyway_schema_history"), "严禁手工写 Flyway 历史");
    }

    private int countOccurrences(String source, String token) {
        int count = 0;
        int from = 0;
        while ((from = source.indexOf(token, from)) >= 0) {
            count++;
            from += token.length();
        }
        return count;
    }
}