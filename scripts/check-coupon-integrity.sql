-- =====================================================================
-- 优惠券数据完整性巡检（只读）
-- 生成日期：2026-10-15
--
-- 【用途】防复发巡检：把 2026-10-15「已使用里出现没有订单的券」这类
--        脏数据 / 不变量破坏，在用户发现之前查出来。
--
-- 【用法】
--   mysql -h127.0.0.1 -uwuling -p wuling < scripts/check-coupon-integrity.sql
--   或容器内：
--   docker exec -i wuling-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" wuling \
--     < /opt/wuling/scripts/check-coupon-integrity.sql
--
-- 【怎么判读】
--   · 第 1 段每个巡检项的 result 必须是 PASS（共 3 项）；出现 FAIL 即为异常；
--   · 第 2 段是明细，正常应返回 0 行；
--   · 第 4 段核对唯一键是否健在（应 2 列 user_id + active_coupon_id，且 result = PASS）。
--
-- 【FAIL 了怎么办】
--   · 「已使用但没有订单号」：说明有数据绕过了业务写入（造数/脚本直写库）。
--     按有效期归位：scripts/fix-coupon-fake-used.sql（含备份 / 审计 / 校验 / 回滚）。
--     正常核销必然带 lock_order_id —— consumeLocked 的 where 就要求 lock_order_id 匹配。
--   · 「同用户同模板多张有效持有券」：先查第 4 段索引是否存在，缺失则补
--     UNIQUE KEY uk_user_coupon_active (user_id, active_coupon_id)。
--
-- 【自动巡检】后端 CouponIntegrityMonitor 每 10 分钟巡检同样两项
--   （app.coupon-integrity-check），异常会写 alerts.log 并置位 ALERT_PENDING，
--   由 scripts/alert-check.sh 以 exit code 暴露给外部监控。
-- =====================================================================

-- ---------- 1. 汇总（result 必须全为 PASS） ----------
select '1.已使用但没有订单号（脏数据 / 直写库）' as item,
       '= 0' as expect,
       count(*) as actual,
       if(count(*) = 0, 'PASS', 'FAIL') as result
from user_coupon
where deleted = 0 and status = 'USED' and lock_order_id is null
union all
select '2.同用户同模板多张有效持有券（唯一键不变量）',
       '= 0',
       count(*),
       if(count(*) = 0, 'PASS', 'FAIL')
from (
    select user_id, coupon_id
    from user_coupon
    where deleted = 0 and status in ('UNUSED', 'LOCKED') and source = 'RECEIVE'
    group by user_id, coupon_id
    having count(*) > 1
) t
union all
select '3.非已使用却带核销时间（use_time 只在 USED 时有意义）',
       '= 0',
       count(*),
       if(count(*) = 0, 'PASS', 'FAIL')
from user_coupon
where deleted = 0 and status <> 'USED' and use_time is not null;

-- ---------- 2. 明细（正常应返回 0 行） ----------
-- 2.1 已使用但没有订单号的券
select 'detail:used_without_order' as tag, id, user_id, coupon_id, source, receive_time, use_time
from user_coupon
where deleted = 0 and status = 'USED' and lock_order_id is null
order by id;

-- 2.2 同用户同模板重复的有效持有券
select 'detail:duplicate_active' as tag, user_id, coupon_id,
       count(*) as cnt, group_concat(id order by id) as user_coupon_ids
from user_coupon
where deleted = 0 and status in ('UNUSED', 'LOCKED') and source = 'RECEIVE'
group by user_id, coupon_id
having count(*) > 1;

-- 2.3 非已使用却带核销时间的券（use_time 只在 status='USED' 时有意义）
select 'detail:non_used_with_use_time' as tag, id, user_id, coupon_id, status, use_time, lock_order_id
from user_coupon
where deleted = 0 and status <> 'USED' and use_time is not null
order by id;

-- ---------- 4. 唯一键健在性（应为 2 列：user_id + active_coupon_id 且 result = PASS） ----------
-- 说明：information_schema.statistics 是「索引列一行」，两列索引因此返回 2 行，属正常。
select 'index:uk_user_coupon_active' as tag,
       count(*) as column_cnt,
       if(count(*) = 2, 'PASS', 'FAIL') as result,
       group_concat(column_name order by seq_in_index) as columns_in_index
from information_schema.statistics
where table_schema = database()
  and table_name = 'user_coupon'
  and index_name = 'uk_user_coupon_active';