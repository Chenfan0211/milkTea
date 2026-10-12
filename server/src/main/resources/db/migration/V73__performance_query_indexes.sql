-- =============================================================
-- P1 性能优化：查询索引补充
--
-- 背景：接口慢排查发现两处缺索引导致的非主键全表扫描：
--   1. product_store 唯一键是 (product_id, store_subject_id)，product_id 在前，
--      但菜单按门店过滤时用 store_subject_id 单列查询
--      （ProductStoreMapper.selectListedProductIds），命中不了该唯一键，走全表扫描；
--   2. 门店排队杯数按「门店 + 状态 + 支付时间」批量统计
--      （OrderMapper.sumRecentPendingVerifyQuantities 的 GROUP BY），
--      orders 仅有 idx_orders_store(store_subject_id) 单列索引，
--      状态与支付时间的过滤仍需回表扫描。
-- 幂等：通过 information_schema 判断后再创建。
-- =============================================================

DROP PROCEDURE IF EXISTS wuling_add_perf_indexes;
DELIMITER $$
CREATE PROCEDURE wuling_add_perf_indexes()
BEGIN
    -- 1) product_store：按门店查已上架商品
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE()
                     AND table_name = 'product_store'
                     AND index_name = 'idx_product_store_store') THEN
        ALTER TABLE product_store ADD INDEX idx_product_store_store (store_subject_id, deleted);
    END IF;

    -- 2) orders：门店排队杯数（近 1 小时 PAID 订单）
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE()
                     AND table_name = 'orders'
                     AND index_name = 'idx_orders_store_status_paytime') THEN
        ALTER TABLE orders ADD INDEX idx_orders_store_status_paytime (store_subject_id, status, pay_time);
    END IF;
END$$
DELIMITER ;
CALL wuling_add_perf_indexes();
DROP PROCEDURE wuling_add_perf_indexes;