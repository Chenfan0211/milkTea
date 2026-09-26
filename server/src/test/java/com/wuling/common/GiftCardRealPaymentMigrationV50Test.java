package com.wuling.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V50 礼品卡真实支付与退款迁移守卫。 */
class GiftCardRealPaymentMigrationV50Test {

    private static final Path MIGRATION_DIR = Paths.get("src/main/resources/db/migration");
    private static final Path MIGRATION = MIGRATION_DIR.resolve("V50__gift_card_real_payment.sql");

    private static String normalizedSql(Path path) throws IOException {
        assertTrue(Files.exists(path), "迁移脚本应存在: " + path);
        return Files.readString(path, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .toLowerCase()
                .replaceAll("\\s+", " ");
    }

    private static String normalizedSha256(Path path) throws IOException, NoSuchAlgorithmException {
        String normalized = Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void shouldAddOrderPaymentFieldsIdempotently() throws IOException {
        String sql = normalizedSql(MIGRATION);

        assertTrue(sql.contains("table_name = 'gift_card_order'"), "应检查 gift_card_order 列存在性");
        assertTrue(sql.contains("column_name = 'expire_time'"), "应新增 expire_time");
        assertTrue(sql.contains("add column expire_time datetime null"), "expire_time 应为可空 datetime");
        assertTrue(sql.contains("column_name = 'transaction_id'"), "应新增 transaction_id");
        assertTrue(sql.contains("add column transaction_id varchar(64) null"), "transaction_id 应为可空 varchar(64)");
        assertFalse(sql.contains("add column if not exists"), "MySQL 8 不应依赖 ADD COLUMN IF NOT EXISTS");
    }

    @Test
    void shouldBindOneCardToEachOrderWithUniqueIndex() throws IOException {
        String sql = normalizedSql(MIGRATION);

        assertTrue(sql.contains("column_name = 'order_id'"), "应检查 gift_card.order_id 列存在性");
        assertTrue(sql.contains("alter table gift_card add column order_id bigint unsigned null"),
                "gift_card.order_id 应为可空 bigint unsigned");
        assertTrue(sql.contains("index_name = 'uk_gift_card_order_id'"), "应检查唯一索引存在性");
        assertTrue(sql.contains("add unique key uk_gift_card_order_id (order_id)"),
                "一笔订单只能绑定一张卡");
    }

    @Test
    void shouldCreateGiftCardRefundTableIdempotently() throws IOException {
        String sql = normalizedSql(MIGRATION);

        assertTrue(sql.contains("table_name = 'gift_card_refund'"), "应用 information_schema 检查退款表存在性");
        assertTrue(sql.contains("create table gift_card_refund"), "应创建 gift_card_refund");
        for (String column : new String[]{
                "refund_no", "order_no", "amount", "reason", "status", "wx_refund_id",
                "fail_reason", "finished_time", "create_time", "update_time", "deleted"}) {
            assertTrue(sql.contains(column), "退款表应包含 " + column);
        }
        assertTrue(sql.contains("unique key uk_gift_card_refund_no (refund_no)"), "退款单号必须唯一");
        assertTrue(sql.contains("idx_gift_card_refund_order_status"), "应按订单号和状态提供退款重试查询索引");
        assertTrue(sql.contains("refunding") && sql.contains("failed") && sql.contains("success"),
                "退款状态应覆盖 REFUNDING/FAILED/SUCCESS");
        assertFalse(sql.contains("create table if not exists gift_card_refund"), "退款表也应按 information_schema 判断");
    }

    @Test
    void shouldNormalizeHistoricalAmountsFromSalePrice() throws IOException {
        String sql = normalizedSql(MIGRATION);

        assertTrue(sql.contains("update gift_card_order o join gift_card_denomination d"),
                "应关联礼品卡面额修正订单实付金额");
        assertTrue(sql.contains("set o.amount = d.sale_price"), "订单金额口径必须取后端 sale_price(分)");
        assertTrue(sql.contains("o.amount <> d.sale_price"), "只修正与 sale_price 不一致的历史金额");
        assertTrue(sql.contains("d.sale_price > 0"), "不得用零价覆盖历史有效订单");

        int updateStart = sql.indexOf("update gift_card_order o join gift_card_denomination d");
        assertTrue(updateStart >= 0, "应存在礼品卡订单金额回填语句");
        int updateEnd = sql.indexOf(';', updateStart);
        assertTrue(updateEnd > updateStart, "金额回填语句应以分号结束");
        String amountUpdate = sql.substring(updateStart, updateEnd + 1);
        assertTrue(amountUpdate.contains("o.pay_status = 'unpaid'"),
                "金额回填只能作用于未支付订单，不能覆盖已支付订单");
        assertTrue(amountUpdate.contains("o.status = 'created'"),
                "金额回填只能作用于待支付建单，不能覆盖已核销或退款订单");
    }

    @Test
    void shouldDeterministicallyMatchExistingCards() throws IOException {
        String sql = normalizedSql(MIGRATION);

        assertTrue(sql.contains("row_number() over ( partition by o.user_id, o.denomination_id order by o.create_time asc, o.id asc )"),
                "订单应按用户、面额和创建顺序稳定排名");
        assertTrue(sql.contains("row_number() over ( partition by c.owner_user_id, c.denomination_id"),
                "历史卡也应按用户和面额分组排名");
        assertTrue(sql.contains("case when c.status = 'active' then 0 else 1 end, c.id"),
                "历史卡应 ACTIVE 优先，再按 id 升序匹配");
        assertTrue(sql.contains("update gift_card c join tmp_v50_gift_card_cards"),
                "应把匹配到的历史卡绑定到订单");
        assertTrue(sql.contains("set c.order_id = oo.order_id"), "绑定必须写 gift_card.order_id");
        assertTrue(sql.contains("c.order_id is null"), "不得覆盖已经绑定的卡");
    }

    @Test
    void shouldCreateMissingActiveCardsWithDeterministicUniqueNumbers() throws IOException {
        String sql = normalizedSql(MIGRATION);

        assertTrue(sql.contains("insert into gift_card ( card_no, denomination_id, status, owner_user_id, order_id"),
                "缺少卡的历史已支付订单应补卡并绑定订单");
        assertTrue(sql.contains("concat('card-mig-', lpad(o.id, 20, '0'))"),
                "补卡号必须由订单主键确定性生成，不能依赖不存在的序列");
        assertTrue(sql.contains("'active'"), "补发卡状态应为 ACTIVE");
        assertTrue(sql.contains("not exists ( select 1 from gift_card c where c.order_id = o.id )"),
                "补卡前必须确认订单尚未绑定卡");
        assertTrue(sql.contains("o.pay_status = 'paid'"), "只处理历史已支付订单");
    }

    @Test
    void shouldBeRepeatSafeAndNeverTouchFlywayHistory() throws IOException {
        String sql = normalizedSql(MIGRATION);

        assertTrue(sql.contains("information_schema.columns"), "新增列必须按 information_schema 判断");
        assertTrue(sql.contains("information_schema.statistics"), "唯一索引必须按 information_schema 判断");
        assertTrue(sql.contains("information_schema.tables"), "退款表必须按 information_schema 判断");
        assertTrue(sql.contains("drop temporary table if exists tmp_v50_gift_card_orders"),
                "临时订单映射表应在重复执行时安全重建");
        assertTrue(sql.contains("drop temporary table if exists tmp_v50_gift_card_cards"),
                "临时卡映射表应在重复执行时安全重建");
        assertFalse(sql.contains("flyway_schema_history"), "严禁手工写 Flyway 历史");
        assertFalse(sql.contains("create table if not exists gift_card_refund"), "不得依赖 CREATE TABLE IF NOT EXISTS");
        assertFalse(sql.contains("drop index if exists"), "不得依赖 DROP INDEX IF EXISTS");
    }

    @Test
    void shouldNotModifyPreviouslyExecutedV48AndV49() throws IOException, NoSuchAlgorithmException {
        assertEquals("a64d0e46896bad03aaff983b7b8b3482a2e6d307e806d87ab38f64055a63277c",
                normalizedSha256(MIGRATION_DIR.resolve("V48__gift_card_admin_management.sql")),
                "V48 已执行迁移内容不得修改");
        assertEquals("14b12934013bfd1d8d7e4bdd90c55836d3e4152c50ace34119f6b94d0f9cd4b3",
                normalizedSha256(MIGRATION_DIR.resolve("V49__widen_gift_card_face_metadata.sql")),
                "V49 已执行迁移内容不得修改");
    }
}
