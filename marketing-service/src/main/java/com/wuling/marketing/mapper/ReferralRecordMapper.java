package com.wuling.marketing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.marketing.entity.ReferralRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import org.apache.ibatis.annotations.Select;

public interface ReferralRecordMapper extends BaseMapper<ReferralRecord> {

    /** 锁定被邀请人的邀请记录，供首单发奖做幂等判定。 */
    @Select("select * from referral_record "
            + "where invitee_user_id = #{inviteeUserId} and deleted = 0 limit 1 for update")
    ReferralRecord selectByInviteeForUpdate(@Param("inviteeUserId") Long inviteeUserId);

    /**
     * 建立邀请首单记录。
     *
     * <p>唯一键 uk_referral_invitee 是并发幂等边界：同一被邀请人只允许一行。
     * 返回 0 表示并发消费者已抢先创建，调用方需要回查该行后继续发奖。
     */
    @Insert("insert ignore into referral_record "
            + "(inviter_user_id, invitee_user_id, status, first_order_status) "
            + "values (#{inviterUserId}, #{inviteeUserId}, 'PENDING', 'UNPAID')")
    int insertIgnore(@Param("inviterUserId") Long inviterUserId,
                     @Param("inviteeUserId") Long inviteeUserId);

    /**
     * 补偿候选：已绑定邀请人、但尚无 referral_record 且已有已支付订单的被邀请人。
     *
     * <p>用于补发任务修复「好友先付款、后绑定邀请人」导致的奖励永久漏发。
     * orders 与 app_user 同库（见 application-dev.yml 的 /wuling），
     * 这里按 user_id 关联取该用户最早一笔已支付订单号作为补发依据。
     *
     * <p>只取 limit 条，避免一次扫描过多；由上层逐条走 {@code rewardFirstOrder}，
     * 该方法自身带唯一键 + 行锁幂等，重复执行安全。
     */
    @Select("select u.id as inviteeUserId, o.order_no as orderNo "
            + "from app_user u "
            + "join orders o on o.user_id = u.id and o.deleted = 0 "
            + "where u.deleted = 0 and u.referrer_id is not null "
            + "and not exists (select 1 from referral_record r "
            + "                where r.invitee_user_id = u.id and r.deleted = 0) "
            + "and o.pay_time is not null "
            + "and o.status in ('PAID','COMPLETED') "
            // 退款口径：in-flight（PENDING）与已退款（REFUNDED）都不能算「有效首单」，
            // 否则会给已退款的订单补发奖励券，造成资损。
            + "and (o.refund_status is null or o.refund_status not in ('PENDING','REFUNDED')) "
            + "and o.id = (select min(o2.id) from orders o2 "
            + "            where o2.user_id = u.id and o2.deleted = 0 "
            + "            and o2.pay_time is not null and o2.status in ('PAID','COMPLETED') "
            + "            and (o2.refund_status is null or o2.refund_status not in ('PENDING','REFUNDED'))) "
            + "order by u.id "
            + "limit #{limit}")
    List<java.util.Map<String, Object>> selectMissingRewardCandidates(@Param("limit") int limit);
}
