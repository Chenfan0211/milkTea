-- 线上数据库字段注释补齐（修复字符串默认值引号）
-- 覆盖 66 张业务表、627 个缺注释字段

ALTER TABLE `app_config`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `sort` int NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
  MODIFY COLUMN `status` varchar(16) NOT NULL DEFAULT 'enabled' COMMENT '状态 enabled启用 disabled停用',
  MODIFY COLUMN `remark` varchar(255) NULL COMMENT '备注',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `app_user`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `open_id` varchar(64) NOT NULL COMMENT '微信openid',
  MODIFY COLUMN `union_id` varchar(64) NULL COMMENT '微信unionid',
  MODIFY COLUMN `nick_name` varchar(64) NULL COMMENT '昵称',
  MODIFY COLUMN `phone` varchar(20) NULL COMMENT '手机号',
  MODIFY COLUMN `birthday` date NULL COMMENT '生日',
  MODIFY COLUMN `gender` varchar(16) NULL COMMENT '性别',
  MODIFY COLUMN `vip_level` varchar(16) NULL COMMENT '会员等级编码',
  MODIFY COLUMN `business_role` varchar(32) NULL COMMENT '业务角色',
  MODIFY COLUMN `bound_subject_id` bigint unsigned NULL COMMENT '绑定的主体ID',
  MODIFY COLUMN `referrer_id` bigint unsigned NULL COMMENT '推荐人用户ID',
  MODIFY COLUMN `channel_subject_id` bigint unsigned NULL COMMENT '渠道主体ID',
  MODIFY COLUMN `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态 1启用 0停用',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `audit_log`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `operator` varchar(64) NULL COMMENT '操作人',
  MODIFY COLUMN `module` varchar(64) NULL COMMENT '操作模块',
  MODIFY COLUMN `action` varchar(64) NULL COMMENT '操作动作',
  MODIFY COLUMN `target` varchar(255) NULL COMMENT '操作对象',
  MODIFY COLUMN `before_value` text NULL COMMENT '变更前值',
  MODIFY COLUMN `after_value` text NULL COMMENT '变更后值',
  MODIFY COLUMN `reason` varchar(255) NULL COMMENT '原因',
  MODIFY COLUMN `ip` varchar(64) NULL COMMENT '操作IP',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `balance_pay_intent`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `biz_role`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `code` varchar(64) NOT NULL COMMENT '角色编码',
  MODIFY COLUMN `name` varchar(64) NOT NULL COMMENT '角色名称',
  MODIFY COLUMN `description` varchar(255) NULL COMMENT '角色描述',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `biz_subject`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `code` varchar(64) NOT NULL COMMENT '主体编码',
  MODIFY COLUMN `name` varchar(128) NOT NULL COMMENT '主体名称',
  MODIFY COLUMN `status` varchar(32) NULL DEFAULT 'active' COMMENT '状态 active启用/disabled停用',
  MODIFY COLUMN `bound_user_id` bigint unsigned NULL COMMENT '绑定的用户ID',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `channel_profile`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `subject_id` bigint unsigned NOT NULL COMMENT '主体ID',
  MODIFY COLUMN `location` varchar(64) NULL COMMENT '位置/区域',
  MODIFY COLUMN `store_type` varchar(64) NULL COMMENT '门店类型',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `channel_store`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `channel_subject_id` bigint unsigned NOT NULL COMMENT '渠道主体ID',
  MODIFY COLUMN `store_subject_id` bigint unsigned NOT NULL COMMENT '门店主体ID',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `comments`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `order_id` bigint unsigned NOT NULL COMMENT '订单ID',
  MODIFY COLUMN `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
  MODIFY COLUMN `rating` int NOT NULL DEFAULT 5 COMMENT '评分',
  MODIFY COLUMN `content` text NULL COMMENT '评论内容',
  MODIFY COLUMN `images` json NULL COMMENT '评论图片(JSON)',
  MODIFY COLUMN `review_time` datetime NULL COMMENT '审核时间',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `coupon`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `code` varchar(64) NOT NULL COMMENT '优惠券编码',
  MODIFY COLUMN `name` varchar(128) NOT NULL COMMENT '优惠券名称',
  MODIFY COLUMN `type` varchar(32) NULL COMMENT '优惠券类型',
  MODIFY COLUMN `amount` bigint NOT NULL DEFAULT 0 COMMENT '面额（分）',
  MODIFY COLUMN `threshold` bigint NOT NULL DEFAULT 0 COMMENT '使用门槛（分）',
  MODIFY COLUMN `brand` varchar(64) NULL COMMENT '品牌',
  MODIFY COLUMN `scenes` varchar(64) NULL COMMENT '适用场景',
  MODIFY COLUMN `source` varchar(64) NULL COMMENT '来源',
  MODIFY COLUMN `description` text NULL COMMENT '描述',
  MODIFY COLUMN `image` varchar(255) NULL COMMENT '图片URL',
  MODIFY COLUMN `validity_type` varchar(16) NULL COMMENT '有效期类型',
  MODIFY COLUMN `validity_start` datetime NULL COMMENT '有效期开始',
  MODIFY COLUMN `validity_end` datetime NULL COMMENT '有效期结束',
  MODIFY COLUMN `validity_days` int NULL COMMENT '有效期天数',
  MODIFY COLUMN `usage_time` varchar(64) NULL COMMENT '可用时段',
  MODIFY COLUMN `applicable_store_ids` json NULL COMMENT '适用门店ID(JSON)',
  MODIFY COLUMN `applicable_product_ids` json NULL COMMENT '适用商品ID(JSON)',
  MODIFY COLUMN `stock` int NOT NULL DEFAULT 0 COMMENT '库存',
  MODIFY COLUMN `status` varchar(16) NOT NULL DEFAULT 'enabled' COMMENT '状态 enabled启用 disabled停用',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `event_outbox`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE `exchange_order`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `exchange_no` varchar(64) NOT NULL COMMENT '兑换单号',
  MODIFY COLUMN `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
  MODIFY COLUMN `points_product_id` bigint unsigned NOT NULL COMMENT '积分商品ID',
  MODIFY COLUMN `points` bigint NOT NULL DEFAULT 0 COMMENT '消耗积分',
  MODIFY COLUMN `pickup_code` varchar(64) NULL COMMENT '核销码',
  MODIFY COLUMN `status` varchar(32) NOT NULL DEFAULT 'PENDING' COMMENT '状态 PENDING待处理/COMPLETED已完成等',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `feature_flag`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `code` varchar(64) NOT NULL COMMENT '开关编码',
  MODIFY COLUMN `name` varchar(64) NOT NULL COMMENT '开关名称',
  MODIFY COLUMN `default_status` varchar(16) NULL COMMENT '默认状态',
  MODIFY COLUMN `current_status` varchar(16) NULL COMMENT '当前状态',
  MODIFY COLUMN `open_condition` varchar(255) NULL COMMENT '开启条件',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `fund_flow`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `flow_no` varchar(64) NOT NULL COMMENT '流水号',
  MODIFY COLUMN `subject_id` bigint unsigned NOT NULL COMMENT '主体ID',
  MODIFY COLUMN `role_type` varchar(32) NOT NULL COMMENT '角色类型',
  MODIFY COLUMN `direction` varchar(8) NOT NULL COMMENT '方向 IN入 OUT出',
  MODIFY COLUMN `amount` bigint NOT NULL DEFAULT 0 COMMENT '金额（分）',
  MODIFY COLUMN `order_no` varchar(64) NULL COMMENT '订单号',
  MODIFY COLUMN `balance_after` bigint NOT NULL DEFAULT 0 COMMENT '变动后余额（分）',
  MODIFY COLUMN `remark` varchar(255) NULL COMMENT '备注',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `fund_pool`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `pool_name` varchar(64) NOT NULL COMMENT '资金池名称',
  MODIFY COLUMN `total_balance` bigint NOT NULL DEFAULT 0 COMMENT '总余额（分）',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `gift_card`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `card_no` varchar(64) NOT NULL COMMENT '卡号',
  MODIFY COLUMN `denomination_id` bigint unsigned NOT NULL COMMENT '面额ID',
  MODIFY COLUMN `status` varchar(32) NOT NULL DEFAULT 'INACTIVE' COMMENT '状态 INACTIVE未激活/ACTIVE已激活等',
  MODIFY COLUMN `owner_user_id` bigint unsigned NULL COMMENT '持卡用户ID',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `gift_card_denomination`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `code` varchar(64) NOT NULL COMMENT '面额编码',
  MODIFY COLUMN `name` varchar(128) NOT NULL COMMENT '面额名称',
  MODIFY COLUMN `amount` bigint NOT NULL DEFAULT 0 COMMENT '面额金额（分）',
  MODIFY COLUMN `status` varchar(16) NOT NULL DEFAULT 'enabled' COMMENT '状态 enabled启用 disabled停用',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `gift_card_group`
  MODIFY COLUMN `code` varchar(64) NOT NULL COMMENT '分组编码',
  MODIFY COLUMN `name` varchar(128) NOT NULL COMMENT '分组名称',
  MODIFY COLUMN `sort` int NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE `gift_card_order`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `order_no` varchar(64) NOT NULL COMMENT '购买单号',
  MODIFY COLUMN `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
  MODIFY COLUMN `denomination_id` bigint unsigned NOT NULL COMMENT '面额ID',
  MODIFY COLUMN `amount` bigint NOT NULL DEFAULT 0 COMMENT '金额（分）',
  MODIFY COLUMN `pay_status` varchar(16) NOT NULL DEFAULT 'UNPAID' COMMENT '支付状态 UNPAID未支付/PAID已支付',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `gift_card_refund`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `refund_no` varchar(64) NOT NULL COMMENT '退款单号',
  MODIFY COLUMN `order_no` varchar(64) NOT NULL COMMENT '购买单号',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `growth_record`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
  MODIFY COLUMN `order_no` varchar(64) NOT NULL COMMENT '订单号',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `investor_profile`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `subject_id` bigint unsigned NOT NULL COMMENT '主体ID',
  MODIFY COLUMN `investable_store_count` int NOT NULL DEFAULT 0 COMMENT '可投门店数',
  MODIFY COLUMN `sign_status` varchar(32) NULL COMMENT '签约状态',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `member_level`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `level_code` varchar(16) NOT NULL COMMENT '等级编码',
  MODIFY COLUMN `name` varchar(64) NOT NULL COMMENT '等级名称',
  MODIFY COLUMN `discount` varchar(16) NULL COMMENT '折扣',
  MODIFY COLUMN `benefits` json NULL COMMENT '权益(JSON)',
  MODIFY COLUMN `sort` int NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `order_item`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `order_id` bigint unsigned NOT NULL COMMENT '订单ID',
  MODIFY COLUMN `product_id` varchar(64) NOT NULL COMMENT '商品ID',
  MODIFY COLUMN `product_name` varchar(128) NOT NULL COMMENT '商品名称',
  MODIFY COLUMN `unit_price` bigint NOT NULL DEFAULT 0 COMMENT '单价（分）',
  MODIFY COLUMN `original_price` bigint NOT NULL DEFAULT 0 COMMENT '原价（分）',
  MODIFY COLUMN `quantity` int NOT NULL DEFAULT 1 COMMENT '数量',
  MODIFY COLUMN `sub_total` bigint NOT NULL DEFAULT 0 COMMENT '小计（分）',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `orders`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `order_no` varchar(64) NOT NULL COMMENT '订单号',
  MODIFY COLUMN `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
  MODIFY COLUMN `store_subject_id` bigint unsigned NOT NULL COMMENT '门店主体ID',
  MODIFY COLUMN `channel_subject_id` bigint unsigned NULL COMMENT '渠道主体ID',
  MODIFY COLUMN `meal_type` varchar(16) NULL COMMENT '餐段类型',
  MODIFY COLUMN `pickup_time` datetime NULL COMMENT '取餐时间',
  MODIFY COLUMN `status` varchar(32) NOT NULL DEFAULT 'CREATED' COMMENT '订单状态',
  MODIFY COLUMN `pay_status` varchar(16) NOT NULL DEFAULT 'UNPAID' COMMENT '支付状态 UNPAID/PAID/REFUNDED等',
  MODIFY COLUMN `pickup_code` varchar(32) NULL COMMENT '取餐码',
  MODIFY COLUMN `total_amount` bigint NOT NULL DEFAULT 0 COMMENT '实付总额（分）',
  MODIFY COLUMN `original_amount` bigint NOT NULL DEFAULT 0 COMMENT '原始总额（分）',
  MODIFY COLUMN `discount_amount` bigint NOT NULL DEFAULT 0 COMMENT '优惠金额（分）',
  MODIFY COLUMN `paid_amount` bigint NOT NULL DEFAULT 0 COMMENT '已支付金额（分）',
  MODIFY COLUMN `coupon_id` bigint unsigned NULL COMMENT '优惠券ID',
  MODIFY COLUMN `coupon_discount` bigint NOT NULL DEFAULT 0 COMMENT '优惠券抵扣（分）',
  MODIFY COLUMN `points_used` bigint NOT NULL DEFAULT 0 COMMENT '使用积分',
  MODIFY COLUMN `points_earned` bigint NOT NULL DEFAULT 0 COMMENT '获得积分',
  MODIFY COLUMN `refund_status` varchar(32) NULL COMMENT '退款状态',
  MODIFY COLUMN `remark` varchar(255) NULL COMMENT '备注',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `pay_time` datetime NULL COMMENT '支付时间',
  MODIFY COLUMN `verify_time` datetime NULL COMMENT '核销时间',
  MODIFY COLUMN `complete_time` datetime NULL COMMENT '完成时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `payment`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `payment_no` varchar(64) NOT NULL COMMENT '支付单号',
  MODIFY COLUMN `amount` bigint NOT NULL DEFAULT 0 COMMENT '金额（分）',
  MODIFY COLUMN `channel` varchar(16) NOT NULL COMMENT '支付渠道',
  MODIFY COLUMN `third_status` varchar(32) NULL COMMENT '第三方状态',
  MODIFY COLUMN `standard_status` varchar(32) NULL COMMENT '标准状态',
  MODIFY COLUMN `transaction_id` varchar(64) NULL COMMENT '第三方交易号',
  MODIFY COLUMN `callback_time` datetime NULL COMMENT '回调时间',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `platform_profile`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `subject_id` bigint unsigned NOT NULL COMMENT '主体ID',
  MODIFY COLUMN `app_id` varchar(64) NULL COMMENT '小程序appid',
  MODIFY COLUMN `app_secret` varchar(128) NULL COMMENT '小程序secret',
  MODIFY COLUMN `pay_config` json NULL COMMENT '支付配置(JSON)',
  MODIFY COLUMN `split_default` json NULL COMMENT '默认分账配置(JSON)',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `points_category`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `points_earning_rule`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `code` varchar(64) NOT NULL COMMENT '规则编码',
  MODIFY COLUMN `action` varchar(128) NOT NULL COMMENT '触发动作',
  MODIFY COLUMN `reward` varchar(64) NULL COMMENT '奖励',
  MODIFY COLUMN `note` varchar(255) NULL COMMENT '说明',
  MODIFY COLUMN `sort` int NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `points_product`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `code` varchar(64) NOT NULL COMMENT '商品编码',
  MODIFY COLUMN `name` varchar(128) NOT NULL COMMENT '商品名称',
  MODIFY COLUMN `image` varchar(255) NULL COMMENT '图片URL',
  MODIFY COLUMN `points` bigint NOT NULL DEFAULT 0 COMMENT '所需积分',
  MODIFY COLUMN `stock` int NOT NULL DEFAULT 0 COMMENT '库存',
  MODIFY COLUMN `badge` varchar(64) NULL COMMENT '角标',
  MODIFY COLUMN `limit_text` varchar(255) NULL COMMENT '限制说明',
  MODIFY COLUMN `description` text NULL COMMENT '描述',
  MODIFY COLUMN `status` varchar(16) NOT NULL DEFAULT 'enabled' COMMENT '状态 enabled启用 disabled停用',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `points_record`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
  MODIFY COLUMN `amount` bigint NOT NULL DEFAULT 0 COMMENT '变动积分',
  MODIFY COLUMN `balance_after` bigint NOT NULL DEFAULT 0 COMMENT '变动后积分',
  MODIFY COLUMN `source` varchar(32) NULL COMMENT '来源',
  MODIFY COLUMN `order_no` varchar(64) NULL COMMENT '订单号',
  MODIFY COLUMN `remark` varchar(255) NULL COMMENT '备注',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `points_signin`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
  MODIFY COLUMN `sign_date` date NOT NULL COMMENT '签到日期',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `points_signin_rule`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `daily` bigint NOT NULL DEFAULT 0 COMMENT '每日积分',
  MODIFY COLUMN `streak_days` int NOT NULL DEFAULT 0 COMMENT '连续天数',
  MODIFY COLUMN `streak_reward` bigint NOT NULL DEFAULT 0 COMMENT '连续奖励积分',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `product`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `product_id` varchar(64) NOT NULL COMMENT '商品业务ID',
  MODIFY COLUMN `code` varchar(64) NOT NULL COMMENT '商品编码',
  MODIFY COLUMN `name` varchar(128) NOT NULL COMMENT '商品名称',
  MODIFY COLUMN `tags` json NULL COMMENT '标签(JSON)',
  MODIFY COLUMN `description` text NULL COMMENT '描述',
  MODIFY COLUMN `price` bigint NOT NULL DEFAULT 0 COMMENT '售价（分）',
  MODIFY COLUMN `original_price` bigint NOT NULL DEFAULT 0 COMMENT '原价（分）',
  MODIFY COLUMN `stored_value_price` bigint NOT NULL DEFAULT 0 COMMENT '储值价（分）',
  MODIFY COLUMN `image` varchar(255) NULL COMMENT '图片URL',
  MODIFY COLUMN `ingredients` varchar(255) NULL COMMENT '配料',
  MODIFY COLUMN `allergens` varchar(255) NULL COMMENT '过敏原',
  MODIFY COLUMN `cup_capacity` varchar(255) NULL COMMENT '杯型/容量',
  MODIFY COLUMN `tips` json NULL COMMENT '提示(JSON)',
  MODIFY COLUMN `on_sale` tinyint NOT NULL DEFAULT 1 COMMENT '1上架 0下架',
  MODIFY COLUMN `split_rule_id` bigint unsigned NULL COMMENT '分账规则ID',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `product_category`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `parent_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '父分类ID',
  MODIFY COLUMN `code` varchar(64) NOT NULL COMMENT '分类编码',
  MODIFY COLUMN `name` varchar(64) NOT NULL COMMENT '分类名称',
  MODIFY COLUMN `sort` int NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `product_spec`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `product_id` bigint unsigned NOT NULL COMMENT '商品ID',
  MODIFY COLUMN `group_code` varchar(64) NOT NULL COMMENT '规格组编码',
  MODIFY COLUMN `group_label` varchar(64) NOT NULL COMMENT '规格组名称',
  MODIFY COLUMN `option_code` varchar(64) NOT NULL COMMENT '规格项编码',
  MODIFY COLUMN `option_label` varchar(64) NOT NULL COMMENT '规格项名称',
  MODIFY COLUMN `price_delta` bigint NOT NULL DEFAULT 0 COMMENT '加价（分）',
  MODIFY COLUMN `selected` tinyint NOT NULL DEFAULT 0 COMMENT '1默认选中 0未选',
  MODIFY COLUMN `icon` varchar(255) NULL COMMENT '图标',
  MODIFY COLUMN `sort` int NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `product_store`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `product_id` bigint unsigned NOT NULL COMMENT '商品ID',
  MODIFY COLUMN `store_subject_id` bigint unsigned NOT NULL COMMENT '门店主体ID',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `reconcile_issue`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `issue_type` varchar(32) NOT NULL COMMENT '异常类型',
  MODIFY COLUMN `order_no` varchar(64) NOT NULL COMMENT '订单号',
  MODIFY COLUMN `system_value` varchar(64) NULL COMMENT '系统值',
  MODIFY COLUMN `third_value` varchar(64) NULL COMMENT '第三方值',
  MODIFY COLUMN `diff_amount` bigint NOT NULL DEFAULT 0 COMMENT '差额（分）',
  MODIFY COLUMN `found_time` datetime NULL COMMENT '发现时间',
  MODIFY COLUMN `status` varchar(32) NULL COMMENT '状态',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `referral_config`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `config` json NULL COMMENT '邀请配置(JSON)',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `referral_record`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `inviter_user_id` bigint unsigned NOT NULL COMMENT '邀请人用户ID',
  MODIFY COLUMN `invitee_user_id` bigint unsigned NOT NULL COMMENT '被邀请人用户ID',
  MODIFY COLUMN `status` varchar(32) NULL COMMENT '状态',
  MODIFY COLUMN `first_order_status` varchar(16) NULL COMMENT '首单状态',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `refund`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `refund_no` varchar(64) NOT NULL COMMENT '退款单号',
  MODIFY COLUMN `order_id` bigint unsigned NOT NULL COMMENT '订单ID',
  MODIFY COLUMN `order_no` varchar(64) NOT NULL COMMENT '订单号',
  MODIFY COLUMN `amount` bigint NOT NULL DEFAULT 0 COMMENT '退款金额（分）',
  MODIFY COLUMN `status` varchar(32) NULL COMMENT '退款状态',
  MODIFY COLUMN `reason` varchar(255) NULL COMMENT '退款原因',
  MODIFY COLUMN `apply_time` datetime NULL COMMENT '申请时间',
  MODIFY COLUMN `review_time` datetime NULL COMMENT '审核时间',
  MODIFY COLUMN `complete_time` datetime NULL COMMENT '完成时间',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `region`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `parent_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '父级ID',
  MODIFY COLUMN `code` varchar(32) NOT NULL COMMENT '区域编码',
  MODIFY COLUMN `name` varchar(64) NOT NULL COMMENT '区域名称',
  MODIFY COLUMN `sort` int NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `role_application`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
  MODIFY COLUMN `role_type` varchar(32) NOT NULL COMMENT '申请角色类型',
  MODIFY COLUMN `status` varchar(32) NOT NULL DEFAULT 'PENDING' COMMENT '状态 PENDING待审核/APPROVED已通过/REJECTED已驳回',
  MODIFY COLUMN `apply_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
  MODIFY COLUMN `review_time` datetime NULL COMMENT '审核时间',
  MODIFY COLUMN `reviewer` varchar(64) NULL COMMENT '审核人',
  MODIFY COLUMN `subject_id` bigint unsigned NULL COMMENT '主体ID',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `settlement_record`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `record_no` varchar(64) NOT NULL COMMENT '结算单号',
  MODIFY COLUMN `subject_id` bigint unsigned NOT NULL COMMENT '主体ID',
  MODIFY COLUMN `snapshot_id` bigint unsigned NULL COMMENT '分账快照ID',
  MODIFY COLUMN `order_id` bigint unsigned NULL COMMENT '订单ID',
  MODIFY COLUMN `amount` bigint NOT NULL DEFAULT 0 COMMENT '金额（分）',
  MODIFY COLUMN `settle_date` date NULL COMMENT '结算日期',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `spec_group_template`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `code` varchar(64) NOT NULL COMMENT '规格组编码',
  MODIFY COLUMN `name` varchar(64) NOT NULL COMMENT '规格组名称',
  MODIFY COLUMN `sort` int NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `spec_option_template`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `group_id` bigint unsigned NOT NULL COMMENT '规格组ID',
  MODIFY COLUMN `code` varchar(64) NOT NULL COMMENT '选项编码',
  MODIFY COLUMN `name` varchar(64) NOT NULL COMMENT '选项名称',
  MODIFY COLUMN `price_delta` bigint NOT NULL DEFAULT 0 COMMENT '加价（分）',
  MODIFY COLUMN `icon` varchar(255) NULL COMMENT '图标',
  MODIFY COLUMN `sort` int NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `split_rule`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `code` varchar(64) NOT NULL COMMENT '规则编码',
  MODIFY COLUMN `name` varchar(128) NOT NULL COMMENT '规则名称',
  MODIFY COLUMN `product_id` bigint unsigned NULL COMMENT '商品ID',
  MODIFY COLUMN `platform_ratio` int NOT NULL DEFAULT 0 COMMENT '平台分账比例(万分比)',
  MODIFY COLUMN `store_ratio` int NOT NULL DEFAULT 0 COMMENT '门店分账比例(万分比)',
  MODIFY COLUMN `channel_ratio` int NOT NULL DEFAULT 0 COMMENT '渠道分账比例(万分比)',
  MODIFY COLUMN `investor_ratio` int NOT NULL DEFAULT 0 COMMENT '投资人分账比例(万分比)',
  MODIFY COLUMN `supplier_ratio` int NOT NULL DEFAULT 0 COMMENT '供应商分账比例(万分比)',
  MODIFY COLUMN `status` varchar(16) NOT NULL DEFAULT 'enabled' COMMENT '状态 enabled启用 disabled停用',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `split_snapshot`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `snapshot_no` varchar(64) NOT NULL COMMENT '快照编号',
  MODIFY COLUMN `order_id` bigint unsigned NOT NULL COMMENT '订单ID',
  MODIFY COLUMN `order_no` varchar(64) NOT NULL COMMENT '订单号',
  MODIFY COLUMN `item_count` int NOT NULL DEFAULT 0 COMMENT '明细数',
  MODIFY COLUMN `platform_amount` bigint NOT NULL DEFAULT 0 COMMENT '平台金额（分）',
  MODIFY COLUMN `store_amount` bigint NOT NULL DEFAULT 0 COMMENT '门店金额（分）',
  MODIFY COLUMN `channel_amount` bigint NOT NULL DEFAULT 0 COMMENT '渠道金额（分）',
  MODIFY COLUMN `investor_amount` bigint NOT NULL DEFAULT 0 COMMENT '投资人金额（分）',
  MODIFY COLUMN `supplier_amount` bigint NOT NULL DEFAULT 0 COMMENT '供应商金额（分）',
  MODIFY COLUMN `platform_commission` bigint NOT NULL DEFAULT 0 COMMENT '平台佣金（分）',
  MODIFY COLUMN `platform_bonus` bigint NOT NULL DEFAULT 0 COMMENT '平台奖励（分）',
  MODIFY COLUMN `total_check` varchar(16) NULL COMMENT '总额校验',
  MODIFY COLUMN `status` varchar(16) NULL COMMENT '状态',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `store_profile`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `subject_id` bigint unsigned NOT NULL COMMENT '主体ID',
  MODIFY COLUMN `city` varchar(64) NULL COMMENT '城市',
  MODIFY COLUMN `address` varchar(255) NULL COMMENT '地址',
  MODIFY COLUMN `phone` varchar(32) NULL COMMENT '联系电话',
  MODIFY COLUMN `latitude` decimal(10,6) NULL COMMENT '纬度',
  MODIFY COLUMN `longitude` decimal(10,6) NULL COMMENT '经度',
  MODIFY COLUMN `store_type` varchar(64) NULL COMMENT '门店类型',
  MODIFY COLUMN `manager` varchar(64) NULL COMMENT '店长',
  MODIFY COLUMN `investor_subject_id` bigint unsigned NULL COMMENT '投资人主体ID',
  MODIFY COLUMN `business_hours` varchar(64) NULL COMMENT '营业时间',
  MODIFY COLUMN `modes` json NULL COMMENT '经营模式(JSON)',
  MODIFY COLUMN `promotion` varchar(255) NULL COMMENT '促销信息',
  MODIFY COLUMN `queue_count` int NOT NULL DEFAULT 0 COMMENT '排队数',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `stored_value_order`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `order_no` varchar(64) NOT NULL COMMENT '充值单号',
  MODIFY COLUMN `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
  MODIFY COLUMN `package_id` bigint unsigned NOT NULL COMMENT '套餐ID',
  MODIFY COLUMN `amount` bigint NOT NULL DEFAULT 0 COMMENT '金额（分）',
  MODIFY COLUMN `pay_status` varchar(16) NOT NULL DEFAULT 'UNPAID' COMMENT '支付状态 UNPAID/PAID',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `stored_value_package`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `code` varchar(64) NOT NULL COMMENT '套餐编码',
  MODIFY COLUMN `name` varchar(128) NOT NULL COMMENT '套餐名称',
  MODIFY COLUMN `amount` bigint NOT NULL DEFAULT 0 COMMENT '金额（分）',
  MODIFY COLUMN `status` varchar(16) NOT NULL DEFAULT 'enabled' COMMENT '状态 enabled启用 disabled停用',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `stored_value_package_coupon`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `package_id` bigint unsigned NOT NULL COMMENT '套餐ID',
  MODIFY COLUMN `coupon_id` bigint unsigned NOT NULL COMMENT '优惠券ID',
  MODIFY COLUMN `count` int NOT NULL DEFAULT 0 COMMENT '赠送数量',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `stored_value_txn`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE `subject_account`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `subject_id` bigint unsigned NOT NULL COMMENT '主体ID',
  MODIFY COLUMN `role_type` varchar(32) NOT NULL COMMENT '角色类型',
  MODIFY COLUMN `available_balance` bigint NOT NULL DEFAULT 0 COMMENT '可用余额（分）',
  MODIFY COLUMN `frozen_balance` bigint NOT NULL DEFAULT 0 COMMENT '冻结余额（分）',
  MODIFY COLUMN `total_income` bigint NOT NULL DEFAULT 0 COMMENT '累计收入（分）',
  MODIFY COLUMN `total_withdrawn` bigint NOT NULL DEFAULT 0 COMMENT '累计提现（分）',
  MODIFY COLUMN `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `supplier_profile`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `subject_id` bigint unsigned NOT NULL COMMENT '主体ID',
  MODIFY COLUMN `status` varchar(32) NULL COMMENT '状态',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `sys_dict_item`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `dict_type` varchar(64) NOT NULL COMMENT '字典类型编码',
  MODIFY COLUMN `item_code` varchar(64) NOT NULL COMMENT '字典项编码',
  MODIFY COLUMN `item_name` varchar(64) NOT NULL COMMENT '字典项名称',
  MODIFY COLUMN `sort` int NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
  MODIFY COLUMN `enabled` tinyint NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `sys_dict_type`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `dict_type` varchar(64) NOT NULL COMMENT '字典类型编码',
  MODIFY COLUMN `dict_name` varchar(64) NOT NULL COMMENT '字典类型名称',
  MODIFY COLUMN `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态 1启用 0停用',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `sys_menu`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `parent_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '父菜单ID',
  MODIFY COLUMN `name` varchar(64) NOT NULL COMMENT '菜单名称',
  MODIFY COLUMN `path` varchar(128) NULL COMMENT '路由路径',
  MODIFY COLUMN `component` varchar(128) NULL COMMENT '组件路径',
  MODIFY COLUMN `perms` varchar(128) NULL COMMENT '权限标识',
  MODIFY COLUMN `type` varchar(16) NOT NULL DEFAULT 'menu' COMMENT '类型 menu菜单/button按钮等',
  MODIFY COLUMN `icon` varchar(64) NULL COMMENT '图标',
  MODIFY COLUMN `order_num` int NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
  MODIFY COLUMN `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态 1启用 0停用',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `sys_role`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `code` varchar(64) NOT NULL COMMENT '角色编码',
  MODIFY COLUMN `name` varchar(64) NOT NULL COMMENT '角色名称',
  MODIFY COLUMN `data_scope` varchar(32) NULL COMMENT '数据权限范围',
  MODIFY COLUMN `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态 1启用 0停用',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `sys_role_menu`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `role_id` bigint unsigned NOT NULL COMMENT '角色ID',
  MODIFY COLUMN `menu_id` bigint unsigned NOT NULL COMMENT '菜单ID',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `sys_user`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `username` varchar(64) NOT NULL COMMENT '用户名',
  MODIFY COLUMN `password` varchar(100) NOT NULL COMMENT '密码（加密）',
  MODIFY COLUMN `nick_name` varchar(64) NULL COMMENT '昵称',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `sys_user_role`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
  MODIFY COLUMN `role_id` bigint unsigned NOT NULL COMMENT '角色ID',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `user_coupon`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
  MODIFY COLUMN `coupon_id` bigint unsigned NOT NULL COMMENT '优惠券ID',
  MODIFY COLUMN `receive_time` datetime NULL COMMENT '领取时间',
  MODIFY COLUMN `lock_order_id` bigint unsigned NULL COMMENT '锁定订单ID',
  MODIFY COLUMN `use_time` datetime NULL COMMENT '使用时间',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `user_role_grant`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
  MODIFY COLUMN `role_code` varchar(64) NOT NULL COMMENT '角色编码',
  MODIFY COLUMN `subject_id` bigint unsigned NOT NULL COMMENT '主体ID',
  MODIFY COLUMN `data_scope` varchar(32) NULL COMMENT '数据权限范围',
  MODIFY COLUMN `grant_by` varchar(64) NULL COMMENT '授权人',
  MODIFY COLUMN `grant_time` datetime NULL COMMENT '授权时间',
  MODIFY COLUMN `status` varchar(32) NULL COMMENT '状态',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `verify_record`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `verify_code` varchar(64) NOT NULL COMMENT '核销码',
  MODIFY COLUMN `order_id` bigint unsigned NULL COMMENT '订单ID',
  MODIFY COLUMN `order_no` varchar(64) NOT NULL COMMENT '订单号',
  MODIFY COLUMN `store_subject_id` bigint unsigned NULL COMMENT '门店主体ID',
  MODIFY COLUMN `operator` varchar(64) NULL COMMENT '操作人',
  MODIFY COLUMN `device` varchar(64) NULL COMMENT '设备',
  MODIFY COLUMN `result` varchar(16) NULL COMMENT '核销结果',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';

ALTER TABLE `withdrawal`
  MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `withdraw_no` varchar(64) NOT NULL COMMENT '提现单号',
  MODIFY COLUMN `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
  MODIFY COLUMN `subject_id` bigint unsigned NOT NULL COMMENT '主体ID',
  MODIFY COLUMN `role_type` varchar(32) NOT NULL COMMENT '角色类型',
  MODIFY COLUMN `amount` bigint NOT NULL DEFAULT 0 COMMENT '提现金额（分）',
  MODIFY COLUMN `fee` bigint NOT NULL DEFAULT 0 COMMENT '手续费（分）',
  MODIFY COLUMN `apply_time` datetime NULL COMMENT '申请时间',
  MODIFY COLUMN `review_time` datetime NULL COMMENT '审核时间',
  MODIFY COLUMN `pay_time` datetime NULL COMMENT '打款时间',
  MODIFY COLUMN `callback_time` datetime NULL COMMENT '回调时间',
  MODIFY COLUMN `failure_reason` varchar(255) NULL COMMENT '失败原因',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删';
