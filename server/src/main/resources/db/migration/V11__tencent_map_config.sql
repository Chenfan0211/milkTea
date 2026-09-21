-- =============================================================
-- 腾讯位置服务密钥入库（敏感配置）
-- 说明：密钥原先放在管理端 .env（VITE_TENCENT_MAP_KEY / VITE_TENCENT_MAP_SK），
--       会随前端构建产物下发，存在泄露风险。
--       现改为存于 app_config，仅由服务端读取并代理调用腾讯接口，
--       密钥不再通过任何接口下发到前端。
-- =============================================================

INSERT INTO app_config (config_key, config_name, value, sort, status, remark) VALUES
('tencent_map_key', '腾讯位置服务密钥', '{
  "key": "DVIBZ-A7X37-GXBX6-PHALK-GVS6V-5HBBQ",
  "sk": "tPEAfvpk5Og0JuylNxIuqcVIpPD4aRB6"
}', 10, 'enabled', '敏感配置：仅服务端读取，禁止通过接口下发');