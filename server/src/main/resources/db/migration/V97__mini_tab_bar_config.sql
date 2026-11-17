-- 小程序底部导航：第三个 Tab 在“会员专区”和“一人一饮”之间由数据库切换。
-- 仅保存受控枚举，页面、文案和图标仍由小程序白名单维护。
INSERT INTO app_config (config_key, config_name, value, sort, status, remark) VALUES
('mini_tab_bar', '小程序底部导航', '{"schemaVersion":1,"thirdTab":"memberZone"}', 9, 'enabled', '控制第三个 Tab；thirdTab 仅支持 memberZone 或 tongueHealth')
ON DUPLICATE KEY UPDATE
  config_name = VALUES(config_name),
  remark = VALUES(remark);
