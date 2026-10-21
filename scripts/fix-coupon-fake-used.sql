-- =====================================================================
-- 优惠券脏数据修复：把「无订单的已使用」券按有效期归位
-- 生成日期：2026-10-15
--
-- 【现象】小程序券包「已使用」Tab 里出现一批写着「已于 xx 过期」的券。
--
-- 【为什么还显示在「已使用」】
--   这些行在库里就是 status = 'USED'，而券包四态口径把 USED 当终态
--   （CouponService#deriveTab：已使用优先），所以归类不会因为过期而改变。
--   上一轮只修了「展示口径」（到期文案改成「已于 xx 过期」），没有改数据。
--
-- 【为什么判定它们是脏数据（而不是真被用过）】
--   应用代码不可能写出「status='USED' 且 lock_order_id 为空」的数据：
--     · 核销 UserCouponMapper.consumeLocked 的 where 要求
--       status='LOCKED' and lock_order_id = #{orderId} —— 正常核销必然带订单标识；
--     · 退款回退 restoreAfterRefund 只会置 UNUSED / EXPIRED，且会清空 lock_order_id。
--   这 8 张的 use_time 又完全相同（2026-09-22 18:56:09），
--   结论：历史演示/批量造数直写库留下的脏数据。
--
-- 【修复口径】（与 CouponService#deriveTab 的四态口径对齐）
--   · 已过有效期 → EXPIRED（进「已过期」Tab）
--   · 仍未过期且**同用户同模板已有有效券** → EXPIRED
--     （唯一键 uk_user_coupon_active = (user_id, active_coupon_id) 只允许同用户同模板一张
--      「有效持有券」，与 CouponService#restoreAfterRefund 的规则一致：回不到 UNUSED 就置 EXPIRED）
--   · 仍未过期且无同模板有效券 → UNUSED（回到「未使用」，可正常使用）
--   · 以上都清空 use_time / lock_order_id（它们从未被真正使用过）
--   · 真实核销的券（lock_order_id 非空）一律不动
--
-- 【安全】执行顺序：0 段预览(只读) → 1 段备份 → 2 段修复 → 3 段审计 → 4 段校验；
--         出问题用 5 段回滚（从备份表恢复）。
-- =====================================================================

-- ---------- 0. 执行前预览（只读，先跑这段确认影响面） ----------
-- 0.1 受影响清单 + 目标状态
select uc.id, uc.user_id, uc.status as status_before,
       case when c.validity_type = 'DAYS'
            then date_add(date_add(uc.receive_time, interval ifnull(c.effective_delay_days, 0) day),
                          interval c.validity_days day)
            else c.validity_end end as expire_at,
       (select count(*) from (select id, user_id, coupon_id, status, source, deleted from user_coupon) o
         where o.id <> uc.id and o.deleted = 0 and o.user_id = uc.user_id and o.coupon_id = uc.coupon_id
           and o.status in ('UNUSED', 'LOCKED') and o.source = 'RECEIVE') as active_same_template,
       case when (c.validity_type = 'DAYS' and c.validity_days > 0 and uc.receive_time is not null
                  and now() > date_add(date_add(uc.receive_time, interval ifnull(c.effective_delay_days, 0) day),
                                       interval c.validity_days day))
             or (c.validity_type = 'RANGE' and c.validity_end is not null and now() > c.validity_end)
            then 'EXPIRED(已过有效期)'
            when (select count(*) from (select id, user_id, coupon_id, status, source, deleted from user_coupon) o
                   where o.id <> uc.id and o.deleted = 0 and o.user_id = uc.user_id and o.coupon_id = uc.coupon_id
                     and o.status in ('UNUSED', 'LOCKED') and o.source = 'RECEIVE') > 0
            then 'EXPIRED(同模板已有有效券)'
            else 'UNUSED(回到未使用)' end as status_after
from user_coupon uc
join coupon c on c.id = uc.coupon_id
where uc.deleted = 0 and uc.status = 'USED' and uc.lock_order_id is null
order by uc.id;

-- 0.2 真实核销券数量（这些必须保持 USED，修复不得影响；修复后要与本值一致）
select count(*) as must_keep_used
from user_coupon
where deleted = 0 and status = 'USED' and lock_order_id is not null;

-- ---------- 1. 备份受影响行（可回滚） ----------
drop table if exists user_coupon_backup_fix_20261015;
create table user_coupon_backup_fix_20261015 as
select id, user_id, coupon_id, status, source, receive_time, use_time, lock_order_id,
       create_time, update_time, now() as backup_time
from user_coupon
where deleted = 0 and status = 'USED' and lock_order_id is null;

-- ---------- 2. 修复（两条 UPDATE 合计覆盖全部受影响行；条件幂等，可重复执行） ----------
-- 2.1 已过有效期 → EXPIRED
update user_coupon uc
join coupon c on c.id = uc.coupon_id
set uc.status = 'EXPIRED', uc.use_time = null, uc.lock_order_id = null, uc.update_time = now()
where uc.deleted = 0 and uc.status = 'USED' and uc.lock_order_id is null
  and ((c.validity_type = 'DAYS' and c.validity_days > 0 and uc.receive_time is not null
        and now() > date_add(date_add(uc.receive_time, interval ifnull(c.effective_delay_days, 0) day),
                             interval c.validity_days day))
    or (c.validity_type = 'RANGE' and c.validity_end is not null and now() > c.validity_end));

-- 2.2 仍未过期：分「同模板已有有效券」与「没有」两种，避免撞唯一键 uk_user_coupon_active
-- 2.2a 同用户同模板已有有效持有券（UNUSED/LOCKED 且 source=RECEIVE）→ EXPIRED
--      （与 CouponService#restoreAfterRefund 的 hasActiveDuplicate 分支同口径）
update user_coupon uc
join coupon c on c.id = uc.coupon_id
set uc.status = 'EXPIRED', uc.use_time = null, uc.lock_order_id = null, uc.update_time = now()
where uc.deleted = 0 and uc.status = 'USED' and uc.lock_order_id is null
  and uc.source = 'RECEIVE'
  and not ((c.validity_type = 'DAYS' and c.validity_days > 0 and uc.receive_time is not null
            and now() > date_add(date_add(uc.receive_time, interval ifnull(c.effective_delay_days, 0) day),
                                 interval c.validity_days day))
        or (c.validity_type = 'RANGE' and c.validity_end is not null and now() > c.validity_end))
  and exists (select 1 from (select id, user_id, coupon_id, status, source, deleted from user_coupon) o
              where o.id <> uc.id and o.deleted = 0 and o.user_id = uc.user_id and o.coupon_id = uc.coupon_id
                and o.status in ('UNUSED', 'LOCKED') and o.source = 'RECEIVE');

-- 2.2b 仍未过期且无同模板有效券 → UNUSED（回到「未使用」，可正常使用）
update user_coupon uc
join coupon c on c.id = uc.coupon_id
set uc.status = 'UNUSED', uc.use_time = null, uc.lock_order_id = null, uc.update_time = now()
where uc.deleted = 0 and uc.status = 'USED' and uc.lock_order_id is null
  and uc.source = 'RECEIVE'
  and not ((c.validity_type = 'DAYS' and c.validity_days > 0 and uc.receive_time is not null
            and now() > date_add(date_add(uc.receive_time, interval ifnull(c.effective_delay_days, 0) day),
                                 interval c.validity_days day))
        or (c.validity_type = 'RANGE' and c.validity_end is not null and now() > c.validity_end))
  and not exists (select 1 from (select id, user_id, coupon_id, status, source, deleted from user_coupon) o
                  where o.id <> uc.id and o.deleted = 0 and o.user_id = uc.user_id and o.coupon_id = uc.coupon_id
                    and o.status in ('UNUSED', 'LOCKED') and o.source = 'RECEIVE');

-- ---------- 3. 审计留痕（一条汇总，避免逐券刷审计页） ----------
insert into audit_log (operator, module, action, target, before_value, after_value, reason, ip)
select 'system', 'COUPON', 'DATA_FIX_FAKE_USED',
       concat('backup:user_coupon_backup_fix_20261015(rows=', count(*), ')'),
       'USED(lock_order_id is null)', 'EXPIRED/UNUSED',
       '无订单的假「已使用」券按有效期归位（历史造数直写库）', 'internal'
from user_coupon_backup_fix_20261015
having count(*) > 0;

-- ---------- 4. 修复后校验 ----------
-- 4.1 应为 0：仍是「已使用但没有订单」的券
select count(*) as should_be_zero_used_without_order
from user_coupon where deleted = 0 and status = 'USED' and lock_order_id is null;

-- 4.2 真实核销券数量（必须与 0.2 相同）
select count(*) as must_keep_used
from user_coupon where deleted = 0 and status = 'USED' and lock_order_id is not null;

-- 4.3 修复后的状态分布
select status, count(*) as cnt from user_coupon where deleted = 0 group by status;

-- 4.4 唯一键不变量：同用户同模板的「有效持有券」不得多于 1 张（应返回 0 行）
select user_id, coupon_id, count(*) as active_cnt
from user_coupon
where deleted = 0 and status in ('UNUSED', 'LOCKED') and source = 'RECEIVE'
group by user_id, coupon_id
having count(*) > 1;

-- 4.5 本次归位结果（按目标状态统计）
select case when uc.status = 'EXPIRED' then 'EXPIRED(已过期)' else 'UNUSED(回到未使用)' end as fixed_to,
       count(*) as cnt
from user_coupon uc
join user_coupon_backup_fix_20261015 b on b.id = uc.id
where uc.deleted = 0
group by fixed_to;

-- ---------- 5. 回滚（仅在需要时执行） ----------
-- update user_coupon uc
-- join user_coupon_backup_fix_20261015 b on b.id = uc.id
-- set uc.status = b.status, uc.use_time = b.use_time, uc.lock_order_id = b.lock_order_id,
--     uc.update_time = b.update_time
-- where uc.deleted = 0;
-- 【注意】回滚不会删除 device/coupon 审计记录；如确需完整还原请连同 audit_log 一并处理。