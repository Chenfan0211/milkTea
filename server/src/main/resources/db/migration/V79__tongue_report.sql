-- =============================================================
-- V79：舌象检测报告
--
-- 背景：
--   user-service 接收小程序舌象图片后，先存 file-service，
--   再调用第三方检测；报告查询可能异步返回，因此本地需要
--   保存第三方 reportId、原始 report JSON 和最近一次拉取状态。
--
-- 表约束：
--   · report_id 唯一，防止第三方同一报告重复落库；
--   · user_id/create_time/deleted 支撑「本人最近 6 个月未删除」查询；
--   · deleted 为逻辑删除，删除接口不物理删行，也不删除 file-service 图片。
--
-- 说明：
--   report_json 完整保存第三方 data 节点，便于对方新增字段后回溯；
--   image_storage_name 只在服务端内部流转，不通过小程序接口返回。
-- =============================================================

CREATE TABLE IF NOT EXISTS tongue_report (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id            BIGINT UNSIGNED NOT NULL COMMENT 'app_user.id',
    report_id          VARCHAR(128)    NOT NULL COMMENT '第三方舌象报告 ID',
    sex                VARCHAR(16)     NOT NULL COMMENT '小程序性别 male/female',
    age                INT             NOT NULL COMMENT '检测时年龄',
    image_storage_name VARCHAR(128)    NOT NULL COMMENT 'file-service 内部 storedName',
    physique           VARCHAR(128)    NULL COMMENT '体质',
    summarize          TEXT            NULL COMMENT '摘要',
    product_list_json  MEDIUMTEXT      NULL COMMENT '商品推荐 JSON',
    report_json        MEDIUMTEXT      NULL COMMENT '第三方 data 原始 JSON',
    report_status      VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/READY/FAILED',
    last_fetch_at      DATETIME        NULL COMMENT '最近一次第三方查询时间',
    create_time        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted            TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tongue_report_report_id (report_id),
    KEY idx_tongue_report_user_time (user_id, create_time, deleted),
    KEY idx_tongue_report_status (report_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='舌象检测报告';
