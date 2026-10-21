-- =====================================================================
-- 演示设备数据清理（茶机设备 / 冲泡记录）
--
-- 用途：
--   联调或对外演示时，会用 scripts/simulate-device-message.ps1 投递
--   「对方文档示例报文」造出一台设备与若干冲泡记录。演示结束后用本脚本
--   把这批数据精确删除，避免与真实设备数据混在一起。
--
-- 口径：
--   · 只按 device_sn 精确匹配，不做时间范围猜测、不做模糊匹配 ——
--     时间范围删除会误伤真实设备在同一时间段的上报；
--   · 四张表都要清：台账、绑定历史、冲泡记录、状态流水
--     （只删台账会让绑定历史/冲泡记录变成无主数据，后台按设备编码仍查得到）；
--   · device_message_error 里的坏报文留档属于排障证据，不在清理范围内。
--
-- 执行（服务器上，库名 wuling）：
--   docker exec -i wuling-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" wuling \
--     < /opt/wuling/scripts/clean-device-demo-data.sql
--
-- 自定义演示设备编码：把下面三处 'ZPDS2512090086G' 替换成实际编码
-- （多个编码用 IN ('A','B')）。
-- =====================================================================

SET @demo_sn = 'ZPDS2512090086G';

-- 1. 胶囊冲泡记录（事实数据，物理删除：本就是演示造数，无审计价值）
DELETE FROM device_brew_record WHERE device_sn = @demo_sn;

-- 2. 在线/离线状态流水
DELETE FROM device_status_log WHERE device_sn = @demo_sn;

-- 3. 设备↔门店绑定历史
DELETE FROM device_store_binding
 WHERE device_id IN (SELECT id FROM device_info WHERE device_sn = @demo_sn)
    OR device_sn = @demo_sn;

-- 4. 设备台账（物理删除：软删会占着唯一键并让「同 SN 上报不再建档」的规则继续生效）
DELETE FROM device_info WHERE device_sn = @demo_sn;

-- 5. 回显剩余条数，便于确认清理干净（正常应全为 0）
SELECT 'device_info' AS table_name, COUNT(*) AS rows_left FROM device_info WHERE device_sn = @demo_sn
UNION ALL
SELECT 'device_brew_record', COUNT(*) FROM device_brew_record WHERE device_sn = @demo_sn
UNION ALL
SELECT 'device_status_log', COUNT(*) FROM device_status_log WHERE device_sn = @demo_sn
UNION ALL
SELECT 'device_store_binding', COUNT(*) FROM device_store_binding WHERE device_sn = @demo_sn;
