-- V49：礼品卡后台分组 code 为 gift-group-<32 hex>（43 字符），而历史
-- gift_card_denomination.group_id 仅 32 字符，新增/编辑卡面会触发 SQL 截断。
-- 不修改已执行的 V48；这里将关联字段扩宽到与 gift_card_group 一致，
-- 同时让 group_title 能完整承载 128 字符分组名称。

ALTER TABLE gift_card_denomination
    MODIFY COLUMN group_id VARCHAR(64) NULL COMMENT '分组 id',
    MODIFY COLUMN group_title VARCHAR(128) NULL COMMENT '分组标题';
