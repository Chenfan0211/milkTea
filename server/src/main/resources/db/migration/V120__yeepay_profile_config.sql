-- =============================================================
-- V120：易宝支付配置纳入平台档案
--
-- 背景：易宝的 appKey / 商编 / 私钥原计划走环境变量，但这些都是运营
-- 可配置项（换商户号、轮换密钥、多环境切换），放环境变量意味着
-- 每次变更都要改部署配置并重启，运维成本高。
--
-- 方案：扩展 platform_profile.pay_config JSON，存易宝全部配置。
-- JSON 列无需改表结构，此处仅补充注释说明字段约定。
--
-- 字段约定（pay_config 内）：
--   wxMchId             微信商户号（既有，保持不变）
--   yeepayAppKey        易宝 YOP 应用标识（明文，非敏感）
--   yeepayMerchantNo    服务商主商编（明文，非敏感）
--   yeepayPlatformMerchantNo 平台商商编（明文，非敏感）
--   yeepayPrivateKey    商户私钥（**AES-256-GCM 加密**，v1: 前缀）
--   yeepayYopPublicKey  易宝平台公钥（明文，公开信息）
--
-- 安全红线：私钥必须加密存储。明文私钥泄露等于他人可冒充本平台
-- 发起支付与提现。加密密钥来自环境变量 APP_SENSITIVE_KEY，
-- 不落库、不入仓库。
-- =============================================================

ALTER TABLE platform_profile MODIFY COLUMN pay_config JSON NULL
    COMMENT '支付配置(JSON)：wxMchId / yeepayAppKey / yeepayMerchantNo / yeepayPlatformMerchantNo / yeepayPrivateKey(加密) / yeepayYopPublicKey';
