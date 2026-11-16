-- 小程序底部导航：按微信上传版本精确控制第三个 Tab。
-- 页面、文案和图标仍由小程序白名单维护；未匹配版本使用 defaultThirdTab。
INSERT INTO app_config (config_key, config_name, value, sort, status, remark) VALUES
('mini_tab_bar', '小程序底部导航',
 '{"schemaVersion":2,"defaultThirdTab":"memberZone","releases":{"1.3.5":"memberZone"}}',
 9, 'enabled', '按版本精确控制第三个 Tab；未匹配版本使用 defaultThirdTab')
ON DUPLICATE KEY UPDATE
  config_name = VALUES(config_name),
  value = CASE
    WHEN JSON_EXTRACT(value, '$.schemaVersion') = 1 THEN VALUES(value)
    ELSE value
  END,
  remark = VALUES(remark);
