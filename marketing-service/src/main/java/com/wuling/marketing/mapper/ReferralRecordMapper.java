package com.wuling.marketing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.marketing.entity.ReferralRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
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
}
