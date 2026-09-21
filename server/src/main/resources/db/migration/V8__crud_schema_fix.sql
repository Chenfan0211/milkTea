-- =============================================================
-- 通用 CRUD 适配：修正被白名单开放写入的表结构约束
-- =============================================================

-- sys_dict_item.dict_type_id 与 dict_type 语义重复：
-- 后台表单以 dict_type / item_code / item_name 维护，dict_type_id 改为可空。
ALTER TABLE sys_dict_item
    MODIFY COLUMN dict_type_id BIGINT UNSIGNED NULL COMMENT '字典类型ID（可空，以 dict_type 为准）';

-- extra 原为 JSON 列，管理端可能写入普通文案，改为 VARCHAR 以避免写入失败
ALTER TABLE sys_dict_item
    MODIFY COLUMN extra VARCHAR(255) NULL COMMENT '扩展信息';

-- region.level 允许后台新增时省略（默认按城市层级 2）
ALTER TABLE region
    MODIFY COLUMN level TINYINT NOT NULL DEFAULT 2 COMMENT '1省 2市 3区';

-- biz_subject.status 给默认值，避免新增主体时必填
ALTER TABLE biz_subject
    MODIFY COLUMN status VARCHAR(32) NULL DEFAULT 'active';
