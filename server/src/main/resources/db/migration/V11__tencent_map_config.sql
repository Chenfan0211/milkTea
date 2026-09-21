-- =============================================================
-- 腾讯位置服务密钥配置项
--
-- 背景：密钥原先放在管理端 .env（VITE_TENCENT_MAP_KEY / VITE_TENCENT_MAP_SK），
--       会随前端构建产物下发，存在泄露风险。
--       现改为存于 app_config，仅由服务端读取并代理调用腾讯接口，
--       密钥不再通过任何接口下发到前端。
--
-- 安全约定：
--   本文件不写入真实密钥。迁移只创建配置项占位，
--   真实 key/sk 在部署时注入（见下方「部署时注入」）。
--
-- 部署时注入（任选其一）：
--   1) 执行 SQL：
--      UPDATE app_config
--         SET value = JSON_OBJECT('key', '<真实Key>', 'sk', '<真实SK>')
--       WHERE config_key = 'tencent_map_key';
--   2) 通过环境变量 TENCENT_MAP_KEY / TENCENT_MAP_SK 由部署脚本写入。
--
-- 注入后需让缓存失效（缓存 TTL 为 1 个月）：
--   调用 AppConfigCacheService#evict("tencent_map_key")，或重启服务。
-- =============================================================

INSERT INTO app_config (config_key, config_name, value, sort, status, remark) VALUES
('tencent_map_key', '腾讯位置服务密钥', '{
  "key": "",
  "sk": ""
}', 10, 'enabled', '敏感配置：仅服务端读取，禁止通过接口下发；真实值部署时注入');