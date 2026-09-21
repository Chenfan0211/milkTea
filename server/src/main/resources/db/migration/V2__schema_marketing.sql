-- =============================================================
-- 五零时光 营销 + 系统字典 表结构（P1/P2）
-- =============================================================

-- ---------- 系统字典 / 省市区 / 开关 / 审计 ----------
CREATE TABLE sys_dict_type (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    dict_type   VARCHAR(64) NOT NULL,
    dict_name   VARCHAR(64) NOT NULL,
    status      TINYINT NOT NULL DEFAULT 1,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_dict_type (dict_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='字典类型';

CREATE TABLE sys_dict_item (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    dict_type_id BIGINT UNSIGNED NOT NULL,
    dict_type    VARCHAR(64) NOT NULL,
    item_code    VARCHAR(64) NOT NULL,
    item_name    VARCHAR(64) NOT NULL,
    sort         INT NOT NULL DEFAULT 0,
    enabled      TINYINT NOT NULL DEFAULT 1,
    extra        JSON NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_sys_dict_item_type (dict_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='字典项';

CREATE TABLE region (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    parent_id   BIGINT UNSIGNED NOT NULL DEFAULT 0,
    code        VARCHAR(32) NOT NULL,
    name        VARCHAR(64) NOT NULL,
    level       TINYINT NOT NULL COMMENT '1省 2市 3区',
    sort        INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_region_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='省市区';

CREATE TABLE feature_flag (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code           VARCHAR(64) NOT NULL,
    name           VARCHAR(64) NOT NULL,
    default_status VARCHAR(16) NULL,
    current_status VARCHAR(16) NULL,
    open_condition VARCHAR(255) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_feature_flag_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='功能开关';

CREATE TABLE audit_log (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    operator     VARCHAR(64) NULL,
    module       VARCHAR(64) NULL,
    action       VARCHAR(64) NULL,
    target       VARCHAR(255) NULL,
    before_value TEXT NULL,
    after_value  TEXT NULL,
    reason       VARCHAR(255) NULL,
    ip           VARCHAR(64) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_audit_log_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审计日志';

-- ---------- 优惠券 ----------
CREATE TABLE coupon (
    id                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code                   VARCHAR(64) NOT NULL,
    name                   VARCHAR(128) NOT NULL,
    type                   VARCHAR(32) NULL,
    amount                 BIGINT NOT NULL DEFAULT 0,
    threshold              BIGINT NOT NULL DEFAULT 0,
    brand                  VARCHAR(64) NULL,
    scenes                 VARCHAR(64) NULL,
    source                 VARCHAR(64) NULL,
    description            TEXT NULL,
    image                  VARCHAR(255) NULL,
    validity_type          VARCHAR(16) NULL,
    validity_start         DATETIME NULL,
    validity_end           DATETIME NULL,
    validity_days          INT NULL,
    usage_time             VARCHAR(64) NULL,
    applicable_store_ids   JSON NULL,
    applicable_product_ids JSON NULL,
    stock                  INT NOT NULL DEFAULT 0,
    status                 VARCHAR(16) NOT NULL DEFAULT 'enabled',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_coupon_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='优惠券模板';

CREATE TABLE user_coupon (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id       BIGINT UNSIGNED NOT NULL,
    coupon_id     BIGINT UNSIGNED NOT NULL,
    status        VARCHAR(16) NOT NULL DEFAULT 'UNUSED' COMMENT 'UNUSED/LOCKED/USED/EXPIRED',
    receive_time  DATETIME NULL,
    lock_order_id BIGINT UNSIGNED NULL,
    use_time      DATETIME NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_user_coupon_user (user_id),
    KEY idx_user_coupon_coupon (coupon_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券';

-- ---------- 储值 ----------
CREATE TABLE stored_value_package (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code        VARCHAR(64) NOT NULL,
    name        VARCHAR(128) NOT NULL,
    amount      BIGINT NOT NULL DEFAULT 0,
    status      VARCHAR(16) NOT NULL DEFAULT 'enabled',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stored_value_package_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='储值套餐';

CREATE TABLE stored_value_package_coupon (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    package_id BIGINT UNSIGNED NOT NULL,
    coupon_id  BIGINT UNSIGNED NOT NULL,
    count      INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_svpc_package (package_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='储值套餐赠送券';

CREATE TABLE stored_value_order (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_no    VARCHAR(64) NOT NULL,
    user_id     BIGINT UNSIGNED NOT NULL,
    package_id  BIGINT UNSIGNED NOT NULL,
    amount      BIGINT NOT NULL DEFAULT 0,
    pay_status  VARCHAR(16) NOT NULL DEFAULT 'UNPAID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stored_value_order_no (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='储值充值单';

-- ---------- 礼品卡 ----------
CREATE TABLE gift_card_denomination (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code        VARCHAR(64) NOT NULL,
    name        VARCHAR(128) NOT NULL,
    amount      BIGINT NOT NULL DEFAULT 0,
    status      VARCHAR(16) NOT NULL DEFAULT 'enabled',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_gift_card_denomination_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='礼品卡面额';

CREATE TABLE gift_card (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    card_no         VARCHAR(64) NOT NULL,
    denomination_id BIGINT UNSIGNED NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'INACTIVE',
    owner_user_id   BIGINT UNSIGNED NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_gift_card_no (card_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='礼品卡';

CREATE TABLE gift_card_order (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_no        VARCHAR(64) NOT NULL,
    user_id         BIGINT UNSIGNED NOT NULL,
    denomination_id BIGINT UNSIGNED NOT NULL,
    amount          BIGINT NOT NULL DEFAULT 0,
    pay_status      VARCHAR(16) NOT NULL DEFAULT 'UNPAID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_gift_card_order_no (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='礼品卡购买单';

-- ---------- 积分 ----------
CREATE TABLE points_product (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code        VARCHAR(64) NOT NULL,
    name        VARCHAR(128) NOT NULL,
    image       VARCHAR(255) NULL,
    points      BIGINT NOT NULL DEFAULT 0,
    stock       INT NOT NULL DEFAULT 0,
    badge       VARCHAR(64) NULL,
    limit_text  VARCHAR(255) NULL,
    description TEXT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'enabled',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_points_product_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='积分商品';

CREATE TABLE points_record (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id       BIGINT UNSIGNED NOT NULL,
    type          VARCHAR(16) NOT NULL COMMENT 'EARN/CONSUME',
    amount        BIGINT NOT NULL DEFAULT 0,
    balance_after BIGINT NOT NULL DEFAULT 0,
    source        VARCHAR(32) NULL,
    order_no      VARCHAR(64) NULL,
    remark        VARCHAR(255) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_points_record_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='时光币流水';

CREATE TABLE points_earning_rule (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code        VARCHAR(64) NOT NULL,
    action      VARCHAR(128) NOT NULL,
    reward      VARCHAR(64) NULL,
    note        VARCHAR(255) NULL,
    sort        INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_points_earning_rule_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='积分获取规则';

CREATE TABLE points_signin_rule (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    daily         BIGINT NOT NULL DEFAULT 0,
    streak_days   INT NOT NULL DEFAULT 0,
    streak_reward BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='签到规则';

CREATE TABLE points_signin (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id     BIGINT UNSIGNED NOT NULL,
    sign_date   DATE NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_points_signin (user_id, sign_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='签到记录';

CREATE TABLE exchange_order (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    exchange_no      VARCHAR(64) NOT NULL,
    user_id          BIGINT UNSIGNED NOT NULL,
    points_product_id BIGINT UNSIGNED NOT NULL,
    points           BIGINT NOT NULL DEFAULT 0,
    pickup_code      VARCHAR(64) NULL,
    status           VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_exchange_order_no (exchange_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='积分兑换单';

-- ---------- 评论 / 邀请 ----------
CREATE TABLE comments (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_id    BIGINT UNSIGNED NOT NULL,
    user_id     BIGINT UNSIGNED NOT NULL,
    rating      INT NOT NULL DEFAULT 5,
    content     TEXT NULL,
    images      JSON NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
    review_time DATETIME NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_comments_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评论';

CREATE TABLE referral_config (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    config      JSON NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='邀请配置';

CREATE TABLE referral_record (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    inviter_user_id    BIGINT UNSIGNED NOT NULL,
    invitee_user_id    BIGINT UNSIGNED NOT NULL,
    status             VARCHAR(32) NULL,
    first_order_status VARCHAR(16) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_referral_invitee (invitee_user_id),
    KEY idx_referral_inviter (inviter_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='邀请记录';
