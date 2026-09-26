-- V48：礼品卡后台分组管理。
--
-- 分组是卡面的权威展示层；历史 gift_card_denomination.group_id/group_title
-- 仍保留以兼容旧数据和购买链路。这里先落表并从现有 popular/limited 回填，
-- 再补卡面聚合查询索引。

CREATE TABLE gift_card_group (
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    sort        INT          NOT NULL DEFAULT 0,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='礼品卡分组';

INSERT INTO gift_card_group (code, name, sort)
SELECT group_id,
       COALESCE(MAX(group_title), group_id) AS name,
       COALESCE(MIN(sort), 0) AS sort
FROM gift_card_denomination
WHERE group_id IN ('popular', 'limited')
GROUP BY group_id;

ALTER TABLE gift_card_denomination
    ADD INDEX idx_gift_card_denomination_face (group_id, card_name, deleted, sort, amount, id);
