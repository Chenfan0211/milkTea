-- =============================================================
-- V75：茶机设备台账 + 胶囊冲泡记录（慧养智萃茶机队列对接）
--
-- 背景：
--   杭州智萃谷通过阿里云轻量消息队列（原 MNS）推送两类数据：
--     1) messagetype=status —— 设备在线/离线；
--     2) messagetype=upload —— 胶囊冲泡记录。
--   报文中【没有门店字段】，只有 deviceSN / deviceName，
--   因此必须先在本地建立设备台账（并与门店绑定），才能把
--   消息归属到门店。未登记设备由消费端自动建档（store_subject_id 为空）。
--
-- 表说明：
--   device_info              设备台账（当前状态 + 当前归属门店）
--   device_store_binding     设备↔门店绑定历史（含门店名快照，一次绑定一条）
--   device_brew_record       冲泡记录（唯一键去重，门店为写入时快照）
--   device_status_log        在线/离线状态变更流水
--   device_message_error     解析失败/未知类型的原始报文留档（避免静默丢数据）
--
-- 约定：
--   · 后台通用 CRUD 的查询语句固定带 deleted = 0，故每张表都要有 deleted 列；
--   · 记录类表的门店名保存「写入时刻快照」，设备换绑后历史记录仍指向当时门店；
--   · 设备台账不冗余门店名，门店改名后列表实时取 biz_subject.name。
-- =============================================================

-- ---------- 设备台账 ----------
CREATE TABLE IF NOT EXISTS device_info (
    id                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    device_sn               VARCHAR(64)     NOT NULL COMMENT '设备编码（报文 deviceSN，设备唯一标识）',
    device_name             VARCHAR(64)     NULL COMMENT '设备ID（报文 deviceName，如 869978072404913）',
    alias                   VARCHAR(64)     NULL COMMENT '运营别名（便于门店识别）',
    remark                  VARCHAR(255)    NULL COMMENT '备注',
    enabled                 TINYINT         NOT NULL DEFAULT 1 COMMENT '1启用 0停用（运营维护）',
    online_status           VARCHAR(16)     NULL COMMENT '在线状态 ONLINE/OFFLINE，由队列消息维护',
    last_report_time        DATETIME        NULL COMMENT '最近一次上报时间（报文 baseCreateTime）',
    last_status_change_time DATETIME        NULL COMMENT '最近一次状态变更时间',
    store_subject_id        BIGINT UNSIGNED NULL COMMENT '当前归属门店 biz_subject.id，NULL=未绑定',
    bind_time               DATETIME        NULL COMMENT '当前绑定生效时间',
    create_time             DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time             DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted                 TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_device_info_sn (device_sn, deleted),
    KEY idx_device_info_store (store_subject_id, deleted),
    KEY idx_device_info_status (online_status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='茶机设备台账（门店可绑定多台）';

-- ---------- 设备↔门店绑定历史 ----------
-- 同一台设备同一时刻只归属一家门店；换绑 = 旧记录写 UNBIND + 新记录写 BIND（同一事务）。
CREATE TABLE IF NOT EXISTS device_store_binding (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    device_id        BIGINT UNSIGNED NOT NULL COMMENT 'device_info.id',
    device_sn        VARCHAR(64)     NOT NULL COMMENT '设备编码（冗余，便于按 SN 排查）',
    store_subject_id BIGINT UNSIGNED NOT NULL COMMENT '门店主体 biz_subject.id',
    store_name       VARCHAR(64)     NULL COMMENT '绑定时刻门店名快照',
    action           VARCHAR(16)     NOT NULL COMMENT 'BIND绑定 UNBIND解绑',
    bind_time        DATETIME        NULL COMMENT '绑定时间',
    unbind_time      DATETIME        NULL COMMENT '解绑时间',
    operator         VARCHAR(64)     NULL COMMENT '操作人（后台登录账号）',
    remark           VARCHAR(255)    NULL COMMENT '操作备注/解绑原因',
    create_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted          TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_device_binding_device (device_id, id),
    KEY idx_device_binding_store (store_subject_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备与门店绑定历史';

-- ---------- 胶囊冲泡记录 ----------
-- 唯一键用于幂等兜底：MQ 只保证 at-least-once，重复投递不得重复入库。
-- tea_code / serial_number 用 NOT NULL DEFAULT '' 而非 NULL：
-- MySQL 唯一索引中 NULL 互不相等，留 NULL 会让去重失效。
CREATE TABLE IF NOT EXISTS device_brew_record (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    device_sn        VARCHAR(64)     NOT NULL COMMENT '设备编码（报文 deviceSN）',
    device_name      VARCHAR(64)     NULL COMMENT '设备ID（报文 deviceName）',
    store_subject_id BIGINT UNSIGNED NULL COMMENT '写入时刻归属门店（快照），NULL=设备未绑定',
    store_name       VARCHAR(64)     NULL COMMENT '写入时刻门店名快照',
    tea_code         VARCHAR(32)     NOT NULL DEFAULT '' COMMENT '茶品代码（报文 teaCode）',
    tea_name         VARCHAR(128)    NULL COMMENT '茶品名称（报文 teaName）',
    customer_code    VARCHAR(64)     NULL COMMENT '客户代码（报文 customer）',
    made_day         VARCHAR(16)     NULL COMMENT '生产日期（报文 madeDay，yyyyMMdd）',
    serial_number    VARCHAR(32)     NOT NULL DEFAULT '' COMMENT '茶品序列号（报文 serialNumber）',
    base_create_time DATETIME        NOT NULL COMMENT '冲泡时间（报文 baseCreateTime）',
    raw_payload      TEXT            NULL COMMENT '原始 payload（JSON 文本，便于追溯）',
    create_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted          TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_device_brew_record (device_sn, base_create_time, tea_code, serial_number),
    KEY idx_device_brew_store_time (store_subject_id, base_create_time),
    KEY idx_device_brew_device_time (device_sn, base_create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='胶囊冲泡记录（设备队列上报）';

-- ---------- 在线/离线状态变更流水 ----------
CREATE TABLE IF NOT EXISTS device_status_log (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    device_sn        VARCHAR(64)     NOT NULL COMMENT '设备编码（报文 deviceSN）',
    online_status    VARCHAR(16)     NOT NULL COMMENT 'ONLINE/OFFLINE',
    store_subject_id BIGINT UNSIGNED NULL COMMENT '写入时刻归属门店（快照）',
    store_name       VARCHAR(64)     NULL COMMENT '写入时刻门店名快照',
    base_create_time DATETIME        NOT NULL COMMENT '状态上报时间（报文 baseCreateTime）',
    create_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted          TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_device_status_log (device_sn, base_create_time),
    KEY idx_device_status_log_device (device_sn, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备在线状态变更流水';

-- ---------- 异常报文留档 ----------
-- 解析失败 / 未知 messagetype 的报文无法进入业务链路，
-- 若不落库，对方改报文格式时会静默丢数据且无从排查。
CREATE TABLE IF NOT EXISTS device_message_error (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    source_message_id VARCHAR(128)    NULL COMMENT '来源消息 ID（MNS messageId）',
    messagetype       VARCHAR(32)     NULL COMMENT '报文 messagetype（解析失败时为空）',
    raw_body          TEXT            NULL COMMENT '原始 messageBody（Base64 原文）',
    error_reason      VARCHAR(255)    NULL COMMENT '失败原因',
    create_time       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted           TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_device_message_error_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备队列异常报文留档';
