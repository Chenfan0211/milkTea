-- 历史订单的归属类型保持空值；历史绑定的来源和绑定时间保持未知。
ALTER TABLE orders
    ADD COLUMN leader_attribution_type VARCHAR(16) NULL COMMENT '商城归属快照: SELF/OTHER/PLATFORM' AFTER leader_user_id;

ALTER TABLE user_leader
    ADD COLUMN binding_source VARCHAR(16) NULL COMMENT '归属来源: SHARE/ADMIN',
    ADD COLUMN bound_at DATETIME NULL COMMENT '最近一次有效绑定时间';
