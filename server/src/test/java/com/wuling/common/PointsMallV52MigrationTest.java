package com.wuling.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V52：积分商城分类与优惠券兑换迁移守卫。 */
class PointsMallV52MigrationTest {

    private static final Path MIGRATION_DIR = Paths.get("src/main/resources/db/migration");
    private static final Path MIGRATION = MIGRATION_DIR.resolve("V52__points_mall_categories_and_coupon_exchange.sql");

    private String normalizedSql() throws IOException {
        assertTrue(Files.exists(MIGRATION), "迁移脚本应存在: " + MIGRATION);
        return Files.readString(MIGRATION, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .toLowerCase()
                .replaceAll("\\s+", " ");
    }

    @Test
    void shouldCreateCategoryTableWithSystemCouponCategory() throws IOException {
        String sql = normalizedSql();

        assertTrue(sql.contains("create table points_category"), "应新建 points_category");
        for (String column : new String[]{
                "code", "name", "sort", "enabled", "system_locked",
                "create_time", "update_time", "deleted"}) {
            assertTrue(sql.contains(column), "points_category 应包含 " + column);
        }
        assertTrue(sql.contains("unique key uk_points_category_code (code)"), "分类编码必须唯一");
        assertTrue(sql.contains("insert into points_category"), "应初始化系统分类");
        assertTrue(sql.contains("('pet', '宠物公益专区', 10, 1, 0)"), "pet 应初始化为宠物公益专区");
        assertTrue(sql.contains("('coupon', '优惠券区', 20, 1, 1)"), "coupon 应初始化为系统锁定分类");

        assertTrue(sql.contains("information_schema.tables"), "建表前应按 information_schema 判断存在性");
        assertTrue(sql.contains("information_schema.columns"), "新增列前应按 information_schema 判断存在性");
        assertTrue(sql.contains("information_schema.statistics"), "新增索引前应按 information_schema 判断存在性");
        assertFalse(sql.contains("create table if not exists points_category"), "不得依赖 CREATE TABLE IF NOT EXISTS");
        assertFalse(sql.contains("add column if not exists"), "MySQL 8 不应依赖 ADD COLUMN IF NOT EXISTS");
    }

    @Test
    void shouldAddProductCouponQuantityAndUserCouponSourceColumns() throws IOException {
        String sql = normalizedSql();

        assertTrue(sql.contains("alter table points_product add column coupon_id bigint unsigned null"),
                "points_product.coupon_id 应为可空外键数值列");
        assertTrue(sql.contains("idx_points_product_coupon"), "points_product.coupon_id 应有索引");
        assertTrue(sql.contains("alter table exchange_order add column quantity int not null default 1"),
                "exchange_order.quantity 应为非空默认 1");
        assertTrue(sql.contains("alter table user_coupon add column source varchar(32) not null default 'receive'"),
                "user_coupon.source 应默认 RECEIVE");
    }

    @Test
    void shouldBackfillSourcesBeforeRebuildingReceiveOnlyActiveCouponIndex() throws IOException {
        String sql = normalizedSql();

        String backfill = "update user_coupon set source = 'receive' where source is null or source = ''";
        String dropIndex = "alter table user_coupon drop index uk_user_coupon_active";
        String dropGenerated = "alter table user_coupon drop column active_coupon_id";
        String addGenerated = "alter table user_coupon add column active_coupon_id bigint unsigned generated always as";
        String receiveRule = "when deleted = 0 and status in ('unused', 'locked') and source = 'receive' then coupon_id else null end";
        String createIndex = "alter table user_coupon add unique key uk_user_coupon_active (user_id, active_coupon_id)";

        assertTrue(sql.contains(backfill), "旧 user_coupon 必须先回填 RECEIVE");
        assertTrue(sql.contains(receiveRule), "活跃券唯一生成列只应覆盖 RECEIVE");
        assertTrue(sql.contains("stored"), "活跃券生成列应保持 STORED");

        int backfillAt = sql.indexOf(backfill);
        int dropIndexAt = sql.indexOf(dropIndex);
        int dropGeneratedAt = sql.indexOf(dropGenerated);
        int addGeneratedAt = sql.indexOf(addGenerated);
        int createIndexAt = sql.indexOf(createIndex);
        assertTrue(backfillAt >= 0 && dropIndexAt > backfillAt,
                "必须先回填旧数据，再删除旧唯一索引");
        assertTrue(dropGeneratedAt > dropIndexAt,
                "绝不能先删除 active_coupon_id 生成列，必须先删除依赖索引");
        assertTrue(addGeneratedAt > dropGeneratedAt,
                "删除旧生成列后再按 RECEIVE 规则重建");
        assertTrue(createIndexAt > addGeneratedAt,
                "重建生成列后再重建唯一索引");
    }

    @Test
    void shouldBindAndDisableHistoricalPointsProducts() throws IOException {
        String sql = normalizedSql();

        assertTrue(sql.contains("join coupon c on c.code = 'coupon-001'"),
                "应通过现有 coupon-001 模板回填绑定关系");
        assertTrue(sql.contains("'points-coupon-3'"), "points-coupon-3 应绑定 3 元券");
        assertTrue(sql.contains("'points-single-cup'"), "points-single-cup 应绑定 3 元券");
        assertTrue(sql.contains("set p.coupon_id = c.id") || sql.contains("set coupon_id = c.id"),
                "绑定必须写入 coupon_id");

        assertTrue(sql.contains("'points-matcha-buy-one'"), "无法准确对应的买一送一商品应先停用");
        assertTrue(sql.contains("'points-second-cup-half'"), "无法准确对应的第二杯半价商品应先停用");
        assertTrue(sql.contains("set status = 'disabled'") || sql.contains("status = 'disabled'"),
                "无法准确绑定的历史券商品应置为 disabled");
    }

    @Test
    void shouldInsertCategoryMenuBeforePointsMallAndGrantOperationRole() throws IOException {
        String sql = normalizedSql();

        assertTrue(sql.contains("'marketing_points-category'"), "应新增积分商城分类菜单");
        assertTrue(sql.contains("'积分商城分类'"), "菜单名称应为积分商城分类");
        assertTrue(sql.contains("'/marketing/points-category'"), "菜单路径应正确");
        assertTrue(sql.contains("'hidden'"), "分类管理应注册为隐藏路由，不出现在左侧菜单");
        assertTrue(sql.contains("'view.marketing_points-category'"), "菜单组件应正确");
        assertTrue(sql.contains("'enable_stored_value'"), "隐藏路由应复用积分商城功能开关权限");
        assertTrue(sql.contains("where not exists") || sql.contains("on duplicate key update"),
                "菜单初始化必须可重复执行");

        for (String nameAndSort : new String[]{
                "marketing_points' then 5", "marketing_member' then 6",
                "marketing_comment' then 7", "marketing_gift-order' then 8",
                "marketing_exchange' then 9", "marketing_referral' then 10"}) {
            assertTrue(sql.contains(nameAndSort), "积分商城及后续菜单排序应顺延: " + nameAndSort);
        }
        assertFalse(sql.contains("'marketing_stored' then"), "储值套餐应保持原排序 2");
        assertFalse(sql.contains("'marketing_gift' then"), "礼品卡应保持原排序 3");

        assertTrue(sql.contains("insert into sys_role_menu"), "应显式补 R_OPERATION 菜单关联");
        assertTrue(sql.contains("r.code = 'r_operation'"), "RBAC 关联应限定 R_OPERATION");
        assertTrue(sql.contains("m.code = 'marketing_points-category'"), "RBAC 关联应指向新菜单");
        assertFalse(sql.contains("flyway_schema_history"), "严禁手工写 Flyway 历史");
    }
}
