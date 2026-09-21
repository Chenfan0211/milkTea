-- =============================================================
-- 五零时光 核心表结构（P0）
-- 约定：金额 BIGINT(分)、比例 INT(万分比)、逻辑删除 deleted=1
-- =============================================================

-- ---------- 系统 / 权限 ----------
CREATE TABLE sys_user (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    username    VARCHAR(64)  NOT NULL,
    password    VARCHAR(100) NOT NULL,
    nick_name   VARCHAR(64)  NULL,
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='后台账号';

CREATE TABLE sys_role (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code        VARCHAR(64) NOT NULL,
    name        VARCHAR(64) NOT NULL,
    data_scope  VARCHAR(32) NULL,
    status      TINYINT     NOT NULL DEFAULT 1,
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_role_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='后台角色';

CREATE TABLE sys_user_role (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id     BIGINT UNSIGNED NOT NULL,
    role_id     BIGINT UNSIGNED NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_role (user_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='后台用户-角色';

CREATE TABLE sys_menu (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    parent_id   BIGINT UNSIGNED NOT NULL DEFAULT 0,
    name        VARCHAR(64)  NOT NULL,
    path        VARCHAR(128) NULL,
    component   VARCHAR(128) NULL,
    perms       VARCHAR(128) NULL,
    type        VARCHAR(16)  NOT NULL DEFAULT 'menu',
    icon        VARCHAR(64)  NULL,
    order_num   INT          NOT NULL DEFAULT 0,
    status      TINYINT      NOT NULL DEFAULT 1,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='后台菜单';

-- ---------- 用户 / 会员 ----------
CREATE TABLE app_user (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    open_id           VARCHAR(64)  NOT NULL,
    union_id          VARCHAR(64)  NULL,
    nick_name         VARCHAR(64)  NULL,
    avatar            VARCHAR(255) NULL,
    phone             VARCHAR(20)  NULL,
    birthday          DATE         NULL,
    gender            VARCHAR(16)  NULL,
    vip_level         VARCHAR(16)  NULL,
    points            BIGINT       NOT NULL DEFAULT 0 COMMENT '时光币',
    balance           BIGINT       NOT NULL DEFAULT 0 COMMENT '储值余额(分)',
    business_role     VARCHAR(32)  NULL,
    bound_subject_id  BIGINT UNSIGNED NULL,
    referrer_id       BIGINT UNSIGNED NULL,
    channel_subject_id BIGINT UNSIGNED NULL,
    status            TINYINT      NOT NULL DEFAULT 1,
    create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted           TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_open_id (open_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='小程序用户';

CREATE TABLE member_level (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    level_code    VARCHAR(16) NOT NULL,
    name          VARCHAR(64) NOT NULL,
    amount_target BIGINT      NOT NULL DEFAULT 0 COMMENT '累计消费门槛(分)',
    discount      VARCHAR(16) NULL,
    benefits      JSON        NULL,
    sort          INT         NOT NULL DEFAULT 0,
    create_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted       TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_member_level_code (level_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会员等级';

-- ---------- 主体 / 授权 ----------
CREATE TABLE biz_subject (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code          VARCHAR(64)  NOT NULL,
    name          VARCHAR(128) NOT NULL,
    subject_type  VARCHAR(32)  NOT NULL COMMENT 'PLATFORM/STORE/CHANNEL/INVESTOR/SUPPLIER',
    status        VARCHAR(32)  NULL,
    bound_user_id BIGINT UNSIGNED NULL,
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_subject_code (code),
    KEY idx_biz_subject_type (subject_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='经营主体';

CREATE TABLE store_profile (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    subject_id          BIGINT UNSIGNED NOT NULL,
    city                VARCHAR(64)  NULL,
    address             VARCHAR(255) NULL,
    phone               VARCHAR(32)  NULL,
    latitude            DECIMAL(10,6) NULL,
    longitude           DECIMAL(10,6) NULL,
    store_type          VARCHAR(64)  NULL,
    business_status     VARCHAR(32)  NULL COMMENT 'open/closed',
    manager             VARCHAR(64)  NULL,
    investor_subject_id BIGINT UNSIGNED NULL,
    business_hours      VARCHAR(64)  NULL,
    modes               JSON         NULL,
    promotion           VARCHAR(255) NULL,
    queue_count         INT          NOT NULL DEFAULT 0,
    create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted             TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_store_profile_subject (subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='门店档案';

CREATE TABLE channel_profile (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    subject_id  BIGINT UNSIGNED NOT NULL,
    location    VARCHAR(64) NULL,
    store_type  VARCHAR(64) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_channel_profile_subject (subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='渠道/资源方档案';

CREATE TABLE channel_store (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    channel_subject_id  BIGINT UNSIGNED NOT NULL,
    store_subject_id    BIGINT UNSIGNED NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_channel_store (channel_subject_id, store_subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='渠道绑定门店';

CREATE TABLE investor_profile (
    id                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    subject_id              BIGINT UNSIGNED NOT NULL,
    investable_store_count  INT NOT NULL DEFAULT 0,
    sign_status             VARCHAR(32) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_investor_profile_subject (subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='投资人档案';

CREATE TABLE supplier_profile (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    subject_id    BIGINT UNSIGNED NOT NULL,
    product_count INT NOT NULL DEFAULT 0,
    status        VARCHAR(32) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_supplier_profile_subject (subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='供应商档案';

CREATE TABLE platform_profile (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    subject_id    BIGINT UNSIGNED NOT NULL,
    app_id        VARCHAR(64)  NULL,
    app_secret    VARCHAR(128) NULL,
    pay_config    JSON NULL,
    split_default JSON NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_profile_subject (subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='平台档案';

CREATE TABLE biz_role (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code        VARCHAR(64) NOT NULL,
    name        VARCHAR(64) NOT NULL,
    description VARCHAR(255) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_role_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='业务角色字典';

CREATE TABLE user_role_grant (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id     BIGINT UNSIGNED NOT NULL,
    role_code   VARCHAR(64) NOT NULL,
    subject_id  BIGINT UNSIGNED NOT NULL,
    data_scope  VARCHAR(32) NULL,
    grant_by    VARCHAR(64) NULL,
    grant_time  DATETIME NULL,
    status      VARCHAR(32) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_user_role_grant_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户业务角色授权';

CREATE TABLE role_application (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id     BIGINT UNSIGNED NOT NULL,
    role_type   VARCHAR(32) NOT NULL,
    status      VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    apply_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    review_time DATETIME NULL,
    reviewer    VARCHAR(64) NULL,
    subject_id  BIGINT UNSIGNED NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_role_application_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色申请';

-- ---------- 商品 / 菜单 ----------
CREATE TABLE product_category (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    parent_id   BIGINT UNSIGNED NOT NULL DEFAULT 0,
    code        VARCHAR(64) NOT NULL,
    name        VARCHAR(64) NOT NULL,
    type        VARCHAR(16) NOT NULL COMMENT 'TAB/GROUP/CATEGORY',
    sort        INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_product_category_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='菜单分类';

CREATE TABLE product (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    product_id         VARCHAR(64) NOT NULL,
    code               VARCHAR(64) NOT NULL,
    name               VARCHAR(128) NOT NULL,
    category_id        BIGINT UNSIGNED NULL,
    tags               JSON NULL,
    description        TEXT NULL,
    price              BIGINT NOT NULL DEFAULT 0,
    original_price     BIGINT NOT NULL DEFAULT 0,
    stored_value_price BIGINT NOT NULL DEFAULT 0,
    image              VARCHAR(255) NULL,
    ingredients        VARCHAR(255) NULL,
    allergens          VARCHAR(255) NULL,
    cup_capacity       VARCHAR(255) NULL,
    tips               JSON NULL,
    on_sale            TINYINT NOT NULL DEFAULT 1,
    split_rule_id      BIGINT UNSIGNED NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_product_id (product_id),
    KEY idx_product_category (category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品';

CREATE TABLE product_store (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    product_id       BIGINT UNSIGNED NOT NULL,
    store_subject_id BIGINT UNSIGNED NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_store (product_id, store_subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品-门店';

CREATE TABLE spec_group_template (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code        VARCHAR(64) NOT NULL,
    name        VARCHAR(64) NOT NULL,
    sort        INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_spec_group_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='规格组模板';

CREATE TABLE spec_option_template (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    group_id    BIGINT UNSIGNED NOT NULL,
    code        VARCHAR(64) NOT NULL,
    name        VARCHAR(64) NOT NULL,
    price_delta BIGINT NOT NULL DEFAULT 0,
    icon        VARCHAR(255) NULL,
    sort        INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_spec_option_group (group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='规格选项模板';

CREATE TABLE product_spec (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    product_id   BIGINT UNSIGNED NOT NULL,
    group_code   VARCHAR(64) NOT NULL,
    group_label  VARCHAR(64) NOT NULL,
    option_code  VARCHAR(64) NOT NULL,
    option_label VARCHAR(64) NOT NULL,
    price_delta  BIGINT NOT NULL DEFAULT 0,
    selected     TINYINT NOT NULL DEFAULT 0,
    icon         VARCHAR(255) NULL,
    sort         INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_product_spec_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品规格';

CREATE TABLE split_rule (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code            VARCHAR(64) NOT NULL,
    name            VARCHAR(128) NOT NULL,
    scope           VARCHAR(16) NOT NULL COMMENT 'GLOBAL/PRODUCT',
    product_id      BIGINT UNSIGNED NULL,
    platform_ratio  INT NOT NULL DEFAULT 0,
    store_ratio     INT NOT NULL DEFAULT 0,
    channel_ratio   INT NOT NULL DEFAULT 0,
    investor_ratio  INT NOT NULL DEFAULT 0,
    supplier_ratio  INT NOT NULL DEFAULT 0,
    status          VARCHAR(16) NOT NULL DEFAULT 'enabled',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_split_rule_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分账规则';

-- ---------- 交易 ----------
CREATE TABLE orders (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_no            VARCHAR(64) NOT NULL,
    user_id             BIGINT UNSIGNED NOT NULL,
    store_subject_id    BIGINT UNSIGNED NOT NULL,
    channel_subject_id  BIGINT UNSIGNED NULL,
    meal_type           VARCHAR(16) NULL,
    pickup_time         DATETIME NULL,
    status              VARCHAR(32) NOT NULL DEFAULT 'CREATED',
    pay_status          VARCHAR(16) NOT NULL DEFAULT 'UNPAID',
    pickup_code         VARCHAR(32) NULL,
    total_amount        BIGINT NOT NULL DEFAULT 0,
    original_amount     BIGINT NOT NULL DEFAULT 0,
    discount_amount     BIGINT NOT NULL DEFAULT 0,
    paid_amount         BIGINT NOT NULL DEFAULT 0,
    coupon_id           BIGINT UNSIGNED NULL,
    coupon_discount     BIGINT NOT NULL DEFAULT 0,
    points_used         BIGINT NOT NULL DEFAULT 0,
    points_earned       BIGINT NOT NULL DEFAULT 0,
    refund_status       VARCHAR(32) NULL,
    remark              VARCHAR(255) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    pay_time    DATETIME NULL,
    verify_time DATETIME NULL,
    complete_time DATETIME NULL,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_orders_order_no (order_no),
    KEY idx_orders_user (user_id),
    KEY idx_orders_store (store_subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单';

CREATE TABLE order_item (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_id        BIGINT UNSIGNED NOT NULL,
    product_id      VARCHAR(64) NOT NULL,
    product_name    VARCHAR(128) NOT NULL,
    spec_snapshot   JSON NULL,
    unit_price      BIGINT NOT NULL DEFAULT 0,
    original_price  BIGINT NOT NULL DEFAULT 0,
    quantity        INT NOT NULL DEFAULT 1,
    sub_total       BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_order_item_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细';

CREATE TABLE payment (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    payment_no       VARCHAR(64) NOT NULL,
    order_id         BIGINT UNSIGNED NOT NULL,
    order_no         VARCHAR(64) NOT NULL,
    amount           BIGINT NOT NULL DEFAULT 0,
    channel          VARCHAR(16) NOT NULL,
    third_status     VARCHAR(32) NULL,
    standard_status  VARCHAR(32) NULL,
    transaction_id   VARCHAR(64) NULL,
    callback_time    DATETIME NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_no (payment_no),
    KEY idx_payment_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='支付单';

CREATE TABLE refund (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    refund_no     VARCHAR(64) NOT NULL,
    order_id      BIGINT UNSIGNED NOT NULL,
    order_no      VARCHAR(64) NOT NULL,
    amount        BIGINT NOT NULL DEFAULT 0,
    status        VARCHAR(32) NULL,
    reason        VARCHAR(255) NULL,
    apply_time    DATETIME NULL,
    review_time   DATETIME NULL,
    complete_time DATETIME NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_no (refund_no),
    KEY idx_refund_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='退款单';

CREATE TABLE verify_record (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    verify_code      VARCHAR(64) NOT NULL,
    order_id         BIGINT UNSIGNED NULL,
    order_no         VARCHAR(64) NOT NULL,
    type             VARCHAR(16) NOT NULL COMMENT 'ORDER/EXCHANGE',
    store_subject_id BIGINT UNSIGNED NULL,
    operator         VARCHAR(64) NULL,
    device           VARCHAR(64) NULL,
    result           VARCHAR(16) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_verify_record_order (order_no),
    KEY idx_verify_record_store (store_subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='核销记录';

-- ---------- 财务 / 分账 / 台账 ----------
CREATE TABLE split_snapshot (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    snapshot_no          VARCHAR(64) NOT NULL,
    order_id             BIGINT UNSIGNED NOT NULL,
    order_no             VARCHAR(64) NOT NULL,
    item_count           INT NOT NULL DEFAULT 0,
    platform_amount      BIGINT NOT NULL DEFAULT 0,
    store_amount         BIGINT NOT NULL DEFAULT 0,
    channel_amount       BIGINT NOT NULL DEFAULT 0,
    investor_amount      BIGINT NOT NULL DEFAULT 0,
    supplier_amount      BIGINT NOT NULL DEFAULT 0,
    platform_commission  BIGINT NOT NULL DEFAULT 0,
    platform_bonus       BIGINT NOT NULL DEFAULT 0,
    total_check          VARCHAR(16) NULL,
    status               VARCHAR(16) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_split_snapshot_no (snapshot_no),
    KEY idx_split_snapshot_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分账快照';

CREATE TABLE subject_account (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    subject_id        BIGINT UNSIGNED NOT NULL,
    role_type         VARCHAR(32) NOT NULL,
    available_balance BIGINT NOT NULL DEFAULT 0,
    frozen_balance    BIGINT NOT NULL DEFAULT 0,
    total_income      BIGINT NOT NULL DEFAULT 0,
    total_withdrawn   BIGINT NOT NULL DEFAULT 0,
    version           INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_subject_account_subject (subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='主体资金账户';

CREATE TABLE fund_flow (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    flow_no          VARCHAR(64) NOT NULL,
    subject_id       BIGINT UNSIGNED NOT NULL,
    role_type        VARCHAR(32) NOT NULL,
    type             VARCHAR(32) NOT NULL COMMENT 'INCOME/SETTLE/FREEZE/UNFREEZE/WITHDRAW/REFUND',
    direction        VARCHAR(8) NOT NULL,
    amount           BIGINT NOT NULL DEFAULT 0,
    order_no         VARCHAR(64) NULL,
    balance_after    BIGINT NOT NULL DEFAULT 0,
    remark           VARCHAR(255) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fund_flow_no (flow_no),
    KEY idx_fund_flow_subject (subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='资金流水';

CREATE TABLE settlement_record (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    record_no   VARCHAR(64) NOT NULL,
    subject_id  BIGINT UNSIGNED NOT NULL,
    snapshot_id BIGINT UNSIGNED NULL,
    order_id    BIGINT UNSIGNED NULL,
    amount      BIGINT NOT NULL DEFAULT 0,
    status      VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SETTLEABLE/FROZEN/SETTLED',
    settle_date DATE NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_settlement_record_no (record_no),
    KEY idx_settlement_subject (subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='结算台账';

CREATE TABLE withdrawal (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    withdraw_no   VARCHAR(64) NOT NULL,
    user_id       BIGINT UNSIGNED NOT NULL,
    subject_id    BIGINT UNSIGNED NOT NULL,
    role_type     VARCHAR(32) NOT NULL,
    amount        BIGINT NOT NULL DEFAULT 0,
    fee           BIGINT NOT NULL DEFAULT 0,
    status        VARCHAR(32) NOT NULL DEFAULT 'APPLIED' COMMENT 'APPLIED/AUDITING/APPROVED/PAID/REJECTED/FAILED',
    apply_time    DATETIME NULL,
    review_time   DATETIME NULL,
    pay_time      DATETIME NULL,
    callback_time DATETIME NULL,
    failure_reason VARCHAR(255) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_withdrawal_no (withdraw_no),
    KEY idx_withdrawal_subject (subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='提现单';

CREATE TABLE fund_pool (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    pool_name     VARCHAR(64) NOT NULL,
    total_balance BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='平台资金池';

CREATE TABLE reconcile_issue (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    issue_type   VARCHAR(32) NOT NULL,
    order_no     VARCHAR(64) NOT NULL,
    system_value VARCHAR(64) NULL,
    third_value  VARCHAR(64) NULL,
    diff_amount  BIGINT NOT NULL DEFAULT 0,
    found_time   DATETIME NULL,
    status       VARCHAR(32) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_reconcile_order (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对账异常';
