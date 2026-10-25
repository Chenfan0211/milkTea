-- =====================================================================
-- V82：移除“我的”页“消息订阅”功能入口
--
-- V81 曾把 subscribe-message 追加到 app_config.profile_functions。
-- 产品要求隐藏该入口，但保留订阅页面本身的代码与模板能力。
-- 本迁移只删除 profile_functions 数组中的 subscribe-message 对象。
-- =====================================================================

SET @profile_value := (
    SELECT value
    FROM app_config
    WHERE config_key = 'profile_functions' AND deleted = 0
    LIMIT 1
);

SET @profile_path := JSON_UNQUOTE(
    JSON_SEARCH(@profile_value, 'one', 'subscribe-message', NULL, '$[*].id')
);

UPDATE app_config
   SET value = JSON_REMOVE(value, @profile_path)
 WHERE config_key = 'profile_functions'
   AND deleted = 0
   AND @profile_path IS NOT NULL;
