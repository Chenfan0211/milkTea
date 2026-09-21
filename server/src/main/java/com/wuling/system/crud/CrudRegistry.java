package com.wuling.system.crud;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通用 CRUD 资源白名单。
 *
 * 设计：后台管理端存在大量「结构相近的配置表增删改」，
 * 与其为每个实体写独立 REST，这里用白名单把「资源名 -> 表名 / 字段映射」登记下来，
 * 由通用控制器统一处理，避免接口数量爆炸。
 *
 * 安全要点：
 * - 只有登记在白名单里的资源可被访问，未知资源直接 404；
 * - 只允许写入登记过的字段，防止越权改敏感列（如 id / deleted / create_time）；
 * - 仍走既有 JWT 鉴权（/api/v1/admin/** 需登录）。
 */
public final class CrudRegistry {

    /**
     * @param resource   前端使用的资源名
     * @param table      实际表名
     * @param writable   允许写入的字段（数据库列名）
     * @param searchable 允许模糊搜索的列
     * @param orderBy    默认排序
     */
    public record Resource(String resource, String table, List<String> writable,
                           List<String> searchable, String orderBy) {
    }

    private static final Map<String, Resource> RESOURCES = Map.ofEntries(
            // ---------- 系统配置 ----------
            Map.entry("dictEntries", new Resource("dictEntries", "sys_dict_item",
                    List.of("dict_type", "item_code", "item_name", "sort", "enabled", "extra"),
                    List.of("item_code", "item_name"), "sort asc, id asc")),
            Map.entry("cities", new Resource("cities", "region",
                    List.of("parent_id", "code", "name", "level", "sort"),
                    List.of("code", "name"), "sort asc, id asc")),
            Map.entry("provinces", new Resource("provinces", "region",
                    List.of("parent_id", "code", "name", "level", "sort"),
                    List.of("code", "name"), "sort asc, id asc")),
            Map.entry("features", new Resource("features", "feature_flag",
                    List.of("code", "name", "default_status", "current_status", "open_condition"),
                    List.of("code", "name"), "id asc")),

            // ---------- 主体管理 ----------
            Map.entry("subjects", new Resource("subjects", "biz_subject",
                    List.of("code", "name", "subject_type", "status", "bound_user_id"),
                    List.of("code", "name"), "id asc")),
            Map.entry("storeTypes", new Resource("storeTypes", "sys_dict_item",
                    List.of("dict_type", "item_code", "item_name", "sort", "enabled", "extra"),
                    List.of("item_code", "item_name"), "sort asc, id asc")),
            Map.entry("users", new Resource("users", "app_user",
                    List.of("nick_name", "phone", "vip_level", "points", "balance",
                            "business_role", "bound_subject_id", "status"),
                    List.of("nick_name", "phone"), "id asc")),

            // ---------- 第3批：商品配置 ----------
            Map.entry("productCategories", new Resource("productCategories", "product_category",
                    List.of("parent_id", "code", "name", "type", "sort"),
                    List.of("code", "name"), "sort asc, id asc")),
            // 规格组模板（与商品级 product_spec 语义不同）
            Map.entry("specGroups", new Resource("specGroups", "spec_group_template",
                    List.of("code", "name", "sort"),
                    List.of("code", "name"), "sort asc, id asc")),
            Map.entry("specs", new Resource("specs", "spec_group_template",
                    List.of("code", "name", "sort"),
                    List.of("code", "name"), "sort asc, id asc")),
            Map.entry("specOptions", new Resource("specOptions", "spec_option_template",
                    List.of("group_id", "code", "name", "price_delta", "icon", "sort"),
                    List.of("code", "name"), "sort asc, id asc")),
            // 分账规则：比例字段为万分比（0~10000，五方合计须为 10000）
            Map.entry("splitRules", new Resource("splitRules", "split_rule",
                    List.of("code", "name", "scope", "product_id", "platform_ratio", "store_ratio",
                            "channel_ratio", "investor_ratio", "supplier_ratio", "status"),
                    List.of("code", "name"), "id asc")),

            // ---------- 第4批：营销配置 ----------
            Map.entry("coupons", new Resource("coupons", "coupon",
                    List.of("code", "name", "type", "amount", "threshold", "brand", "scenes", "source",
                            "description", "image", "validity_type", "validity_days", "usage_time",
                            "applicable_store_ids", "applicable_product_ids", "stock", "status"),
                    List.of("code", "name"), "id asc")),
            Map.entry("storedValuePackages", new Resource("storedValuePackages", "stored_value_package",
                    List.of("code", "name", "amount", "status"),
                    List.of("code", "name"), "id asc")),
            Map.entry("pointsProducts", new Resource("pointsProducts", "points_product",
                    List.of("code", "name", "image", "points", "stock", "badge", "limit_text",
                            "description", "status"),
                    List.of("code", "name"), "id asc")),
            Map.entry("pointsEarningRules", new Resource("pointsEarningRules", "points_earning_rule",
                    List.of("code", "action", "reward", "note", "sort"),
                    List.of("code", "action"), "sort asc, id asc")),
            Map.entry("giftCardDenominations", new Resource("giftCardDenominations", "gift_card_denomination",
                    List.of("code", "name", "amount", "status"),
                    List.of("code", "name"), "id asc")),
            Map.entry("giftCards", new Resource("giftCards", "gift_card_denomination",
                    List.of("code", "name", "amount", "status"),
                    List.of("code", "name"), "id asc")),
            Map.entry("giftCardOrders", new Resource("giftCardOrders", "gift_card_order",
                    List.of("pay_status"),
                    List.of("order_no"), "id desc")),
            Map.entry("memberLevels", new Resource("memberLevels", "member_level",
                    List.of("level_code", "name", "amount_target", "discount", "benefits", "sort"),
                    List.of("level_code", "name"), "sort asc, id asc")),
            Map.entry("categories", new Resource("categories", "product_category",
                    List.of("parent_id", "code", "name", "type", "sort"),
                    List.of("code", "name"), "sort asc, id asc")),

            // ---------- 授权管理 ----------
            Map.entry("roles", new Resource("roles", "sys_role",
                    List.of("code", "name", "data_scope", "status"),
                    List.of("code", "name"), "id asc")),
            Map.entry("grants", new Resource("grants", "user_role_grant",
                    List.of("user_id", "role_code", "subject_id", "data_scope", "grant_by", "status"),
                    List.of("role_code"), "id desc")),

            // ---------- 交易（支付单可标记状态；订单/退款为业务动作接口）----------
            Map.entry("payments", new Resource("payments", "payment",
                    List.of("third_status", "standard_status", "transaction_id"),
                    List.of("order_no", "payment_no"), "id desc")),
            Map.entry("verifies", new Resource("verifies", "verify_record",
                    List.of("result"),
                    List.of("order_no", "verify_code"), "id desc")),

            // ---------- 财务 ----------
            Map.entry("fundPool", new Resource("fundPool", "fund_pool",
                    List.of("pool_name", "total_balance"),
                    List.of("pool_name"), "id asc")),
            Map.entry("fundFlows", new Resource("fundFlows", "fund_flow",
                    List.of("remark"),
                    List.of("flow_no", "order_no"), "id desc")),
            Map.entry("snapshots", new Resource("snapshots", "split_snapshot",
                    List.of("status", "total_check"),
                    List.of("snapshot_no", "order_no"), "id desc")),
            Map.entry("reconciles", new Resource("reconciles", "reconcile_issue",
                    List.of("status"),
                    List.of("order_no"), "id desc")),
            Map.entry("subjectAccounts", new Resource("subjectAccounts", "subject_account",
                    List.of("role_type"),
                    List.of(), "id asc")),
            Map.entry("roleApplications", new Resource("roleApplications", "role_application",
                    List.of("status", "reviewer", "subject_id"),
                    List.of("role_type"), "id desc")),
            Map.entry("withdrawals", new Resource("withdrawals", "withdrawal",
                    List.of("failure_reason"),
                    List.of("withdraw_no"), "id desc")),
            Map.entry("commentsAdmin", new Resource("commentsAdmin", "comments",
                    List.of("status", "review_time"),
                    List.of("content"), "id desc")),
            Map.entry("auditLogs", new Resource("auditLogs", "audit_log",
                    List.of("operator", "module", "action", "target", "reason", "ip"),
                    List.of("module", "action", "target"), "id desc")),

            // ---------- 交易只读视图 ----------
            Map.entry("orders", new Resource("orders", "orders",
                    List.of("remark"),
                    List.of("order_no", "pickup_code"), "id desc")),
            Map.entry("refunds", new Resource("refunds", "refund",
                    List.of("reason"),
                    List.of("refund_no", "order_no"), "id desc")),
            Map.entry("verifyPool", new Resource("verifyPool", "orders",
                    List.of("remark"),
                    List.of("order_no", "pickup_code"), "id desc")),
            Map.entry("verifyRecords", new Resource("verifyRecords", "verify_record",
                    List.of("result"),
                    List.of("order_no", "verify_code"), "id desc")),
            Map.entry("exchangeRecords", new Resource("exchangeRecords", "exchange_order",
                    List.of("status"),
                    List.of("exchange_no", "pickup_code"), "id desc")),
            Map.entry("comments", new Resource("comments", "comments",
                    List.of("status", "review_time"),
                    List.of("content"), "id desc")),
            Map.entry("referralConfig", new Resource("referralConfig", "referral_config",
                    List.of("config"),
                    List.of(), "id desc")),
            Map.entry("signinRules", new Resource("signinRules", "points_signin_rule",
                    List.of("daily", "streak_days", "streak_reward"),
                    List.of(), "id asc")),
            // 运营配置（首页入口 / 活动说明 / 我的页宫格 / 城市 / 签到规则文案）
            Map.entry("appConfig", new Resource("appConfig", "app_config",
                    List.of("config_key", "config_name", "value", "sort", "status", "remark"),
                    List.of("config_key", "config_name"), "sort asc, id asc"))
    );

    private CrudRegistry() {
    }

    public static Resource get(String resource) {
        return RESOURCES.get(resource);
    }

    public static boolean exists(String resource) {
        return RESOURCES.containsKey(resource);
    }

    public static Set<String> allResources() {
        return RESOURCES.keySet();
    }
}



