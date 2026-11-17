-- V97 已在生产执行，保留其原始校验值；在新版本迁移中升级旧版配置。
-- 已使用 schemaVersion=2 的运营配置（含按版本发布的 Tab）不覆盖。
UPDATE app_config
SET value = JSON_OBJECT(
        'schemaVersion', 2,
        'defaultThirdTab', CASE
            WHEN JSON_UNQUOTE(JSON_EXTRACT(value, '$.thirdTab')) = 'tongueHealth'
                THEN 'tongueHealth'
            ELSE 'memberZone'
        END,
        'releases', JSON_OBJECT('1.3.5', 'memberZone')
    ),
    remark = '按版本精确控制第三个 Tab；未匹配版本使用 defaultThirdTab'
WHERE config_key = 'mini_tab_bar' AND deleted = 0
  AND CASE WHEN JSON_VALID(value)
      THEN JSON_UNQUOTE(JSON_EXTRACT(value, '$.schemaVersion'))
      ELSE NULL END = '1';
