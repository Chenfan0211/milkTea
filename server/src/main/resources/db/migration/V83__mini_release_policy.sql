-- V83：小程序按构建版本隐藏功能
-- 配置由后台 app_config 维护；打包时读取并冻结到产物，运行时只允许进一步隐藏已编译功能。
INSERT INTO app_config (config_key, config_name, value, sort, status, remark) VALUES
('mini_release_policy', '小程序版本功能策略', '{"schemaVersion":1,"features":{"tongueHealth":{"hiddenVersions":["1.3.10"],"replacement":"memberZone"}}}', 8, 'enabled', '按构建版本隐藏功能；当前 1.3.10 隐藏舌诊并替换会员专区 Tab')
ON DUPLICATE KEY UPDATE
  config_name = VALUES(config_name),
  value = VALUES(value),
  sort = VALUES(sort),
  status = VALUES(status),
  remark = VALUES(remark);