-- =============================================================
-- V111：经营者商户入驻与协议签署（易宝子商户）
--
-- 背景：易宝「分账 + 自助结算」路线要求每个经营者先入驻为
-- 特约商户（SETTLED_MERCHANT），拿到易宝商户编号后才能作为
-- 分账接收方；入驻审核通过后易宝会下发电子签章地址，经营者
-- 签署完成才开通业务 —— 这一步即「签约」。
--
-- 设计要点：
-- 1. 一主体一条入驻记录（uk_subject_id），换请求号重提也复用同一行；
-- 2. application_status 与易宝「入网结果通知」完全对齐，不做二次映射；
-- 3. 全部字段可用 null，不影响微信收款的既有数据。
-- =============================================================

CREATE TABLE IF NOT EXISTS subject_merchant_onboard (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    subject_id          BIGINT UNSIGNED NOT NULL COMMENT '经营主体 biz_subject.id',
    user_id             BIGINT UNSIGNED NULL COMMENT '提交人 app_user.id',
    request_no          VARCHAR(64) NOT NULL COMMENT '入网请求号（易宝幂等键）',
    application_no      VARCHAR(64) NULL COMMENT '易宝申请单编号',
    yeepay_merchant_no  VARCHAR(32) NULL COMMENT '易宝子商户编号，分账时作为 ledgerNo',
    sign_type           VARCHAR(32) NOT NULL COMMENT 'ENTERPRISE 企业 / INDIVIDUAL 个体 / MICRO 小微',
    business_role       VARCHAR(32) NOT NULL DEFAULT 'SETTLED_MERCHANT'
                        COMMENT 'SETTLED_MERCHANT 入驻商户（可收分账可结算）',
    application_status  VARCHAR(32) NULL
                        COMMENT 'REVIEWING 审核中 / REVIEW_BACK 驳回 / BUSINESS_OPENING 开通中 / COMPLETED 完成',
    sign_url            VARCHAR(512) NULL COMMENT '电子签章地址（入网结果通知下发）',
    signed_time         DATETIME NULL COMMENT '经营者完成签署时间',
    reject_reason       VARCHAR(512) NULL COMMENT '驳回原因（易宝审核意见）',
    notify_raw          JSON NULL COMMENT '最后一次入网结果通知原文，排障用',
    retry_count         INT NOT NULL DEFAULT 0 COMMENT '查询/重提次数',
    last_query_time     DATETIME NULL COMMENT '最后一次主动查询进度时间',
    create_time         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted             TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_onboard_subject (subject_id),
    UNIQUE KEY uk_onboard_request_no (request_no),
    KEY idx_onboard_status (application_status),
    KEY idx_onboard_merchant_no (yeepay_merchant_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='经营者易宝商户入驻与签约';

-- 入驻主体资料（与业务主体解耦：这些是敏感的实名/证照信息，单独存表便于管控访问）
CREATE TABLE IF NOT EXISTS subject_merchant_material (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    subject_id          BIGINT UNSIGNED NOT NULL,
    legal_name          VARCHAR(64) NOT NULL COMMENT '法人/经营者姓名',
    legal_licence_no_enc VARCHAR(512) NOT NULL COMMENT '法人证件号（AES 加密）',
    legal_licence_mask  VARCHAR(32) NOT NULL COMMENT '法人证件号掩码，用于展示',
    legal_licence_front_url VARCHAR(512) NULL COMMENT '证件人像面（易宝资质 URL）',
    legal_licence_back_url  VARCHAR(512) NULL COMMENT '证件非人像面',
    licence_url         VARCHAR(512) NULL COMMENT '营业执照（企业/个体必填）',
    licence_no          VARCHAR(64) NULL COMMENT '统一社会信用代码',
    sign_name           VARCHAR(128) NOT NULL COMMENT '商户签约名（须与证照一致）',
    short_name          VARCHAR(64) NULL COMMENT '商户简称',
    contact_name        VARCHAR(64) NULL COMMENT '联系人姓名',
    contact_mobile_enc  VARCHAR(512) NULL COMMENT '联系人手机号（AES 加密）',
    contact_mobile_mask VARCHAR(32) NULL COMMENT '联系人手机号掩码',
    contact_email       VARCHAR(128) NULL,
    province            VARCHAR(32) NULL COMMENT '经营省',
    city                VARCHAR(32) NULL COMMENT '经营市',
    district            VARCHAR(32) NULL COMMENT '经营区',
    address             VARCHAR(255) NULL COMMENT '经营详细地址',
    create_time         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted             TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_material_subject (subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='经营者入驻资料';
