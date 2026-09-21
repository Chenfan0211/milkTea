-- =============================================================
-- 小程序端运营配置（首页快捷入口 / 活动弹层 / 我的页功能入口 / 活动规则 / 签到奖励）
-- 设计：统一用 app_config 表按 config_key 存储，value 为 JSON，
--       便于后台编辑而无需为每类配置单独建表。
-- =============================================================

CREATE TABLE app_config (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    config_key  VARCHAR(64)  NOT NULL COMMENT '配置键',
    config_name VARCHAR(64)  NOT NULL COMMENT '配置名称（后台展示）',
    value       JSON         NULL COMMENT '配置内容',
    sort        INT          NOT NULL DEFAULT 0,
    status      VARCHAR(16)  NOT NULL DEFAULT 'enabled',
    remark      VARCHAR(255) NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='小程序运营配置';

-- ---------- 首页快捷入口 ----------
INSERT INTO app_config (config_key, config_name, value, sort, remark) VALUES
('home_shortcuts', '首页快捷入口', '[
  {"id":"coupon","label":"会员领券","icon":"/assets/icons/lucide/ticket-percent.svg"},
  {"id":"stored-value","label":"储值有礼","icon":"/assets/icons/lucide/gift.svg"},
  {"id":"points-mall","label":"时光币商城","icon":"/assets/icons/lucide/badge-japanese-yen.svg"},
  {"id":"service","label":"客服入口","icon":"/assets/icons/lucide/headset.svg"}
]', 1, '首页金刚区入口');

-- ---------- 点单页活动弹层 ----------
INSERT INTO app_config (config_key, config_name, value, sort, remark) VALUES
('menu_activity', '点单活动', '{
  "tag":"会员优惠",
  "description":"周四会员日招牌饮品85折",
  "detailTitle":"周四会员日",
  "rules":[
    {"label":"活动时间","value":"每周四"},
    {"label":"活动周期","value":"长期有效"},
    {"label":"活动时段","value":"门店营业时间内"},
    {"label":"总次数限制","value":"不限制"},
    {"label":"每天参与次数","value":"不限制"}
  ],
  "applicableProducts":"点单页标记参与活动的招牌饮品享85折优惠。",
  "excludedProducts":"礼品卡、储值套餐、配送费、包装费及未标记参与活动的商品不参与本优惠。"
}', 2, '点单页活动说明');

-- ---------- 我的页功能入口 ----------
INSERT INTO app_config (config_key, config_name, value, sort, remark) VALUES
('profile_functions', '我的页功能入口', '[
  {"id":"share","label":"分享有礼","icon":"/assets/icons/lucide/share-2.svg"},
  {"id":"coupon-wallet","label":"我的券包","icon":"/assets/icons/lucide/ticket.svg"},
  {"id":"points","label":"时光币兑换","icon":"/assets/icons/lucide/badge-japanese-yen.svg"},
  {"id":"benefits","label":"会员权益","icon":"/assets/icons/lucide/member.svg"},
  {"id":"address","label":"收货地址","icon":"/assets/icons/lucide/map-pinned.svg"},
  {"id":"service","label":"客服中心","icon":"/assets/icons/lucide/headset.svg"},
  {"id":"activity","label":"活动报名","icon":"/assets/icons/lucide/calendar-check.svg"},
  {"id":"cooperation","label":"加盟合作","icon":"/assets/icons/lucide/handshake.svg"}
]', 3, '我的页功能宫格');

-- ---------- 签到规则说明 ----------
INSERT INTO app_config (config_key, config_name, value, sort, remark) VALUES
('signin_rules', '签到规则说明', '[
  "每日签到可领取1时光币；",
  "连续签到每满7天额外获得20时光币。"
]', 4, '签到规则页文案');

-- ---------- 签到奖励档位 ----------
INSERT INTO app_config (config_key, config_name, value, sort, remark) VALUES
('signin_rewards', '签到奖励档位', '[
  {"id":"signin-7","days":7,"title":"20时光币","description":"连续签到7天","type":"points","amount":20}
]', 5, '连续签到奖励');

-- ---------- 城市列表（门店可选城市，含坐标用于就近排序） ----------
-- 说明：region 表存行政区划，城市坐标另用 app_config 承载，避免为坐标改动 region 结构
INSERT INTO app_config (config_key, config_name, value, sort, remark) VALUES
('app_cities', '可选城市', '[
  {"code":"changsha","name":"长沙市","initial":"C","latitude":28.2282,"longitude":112.9388},
  {"code":"guangzhou","name":"广州市","initial":"G","latitude":23.1291,"longitude":113.2644},
  {"code":"shenzhen","name":"深圳市","initial":"S","latitude":22.5431,"longitude":114.0579}
]', 6, '小程序城市选择列表');

