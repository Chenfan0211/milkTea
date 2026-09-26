package com.wuling.marketing.service;

import com.wuling.marketing.entity.ReferralRecord;
import com.wuling.marketing.mapper.ReferralRecordMapper;
import com.wuling.user.entity.AppUser;
import com.wuling.user.mapper.AppUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 好友首单奖励：邀请人与被邀请人各发时光币和奖励券。
 *
 * <p>幂等边界有两层：
 * <ol>
 *   <li>referral_record 按 invitee_user_id 唯一；</li>
 *   <li>user_coupon 的唯一索引保证同一用户不会重复持有同一奖励券。</li>
 * </ol>
 * 支付成功事件由 MQ 重试，任何一层失败都会让事务回滚并允许下一次重试。
 */
@Service
public class ReferralRewardService {

    private static final Logger log = LoggerFactory.getLogger(ReferralRewardService.class);
    private static final String PENDING = "PENDING";
    private static final String COMPLETED = "COMPLETED";
    private static final String UNPAID = "UNPAID";
    private static final String PAID = "PAID";

    private final AppUserMapper appUserMapper;
    private final ReferralRecordMapper referralRecordMapper;
    private final PointsService pointsService;
    private final CouponService couponService;
    private final ReferralConfigService referralConfigService;

    public ReferralRewardService(AppUserMapper appUserMapper,
                                 ReferralRecordMapper referralRecordMapper,
                                 PointsService pointsService,
                                 CouponService couponService,
                                 ReferralConfigService referralConfigService) {
        this.appUserMapper = appUserMapper;
        this.referralRecordMapper = referralRecordMapper;
        this.pointsService = pointsService;
        this.couponService = couponService;
        this.referralConfigService = referralConfigService;
    }

    @Transactional(rollbackFor = Exception.class)
    public void rewardFirstOrder(Long inviteeUserId, String orderNo) {
        if (inviteeUserId == null || orderNo == null || orderNo.isBlank()) {
            log.warn("邀请首单奖励缺少必要参数 inviteeUserId={} orderNo={}", inviteeUserId, orderNo);
            return;
        }

        AppUser invitee = appUserMapper.selectById(inviteeUserId);
        Long inviterUserId = invitee == null ? null : invitee.getReferrerId();
        if (inviterUserId == null || inviterUserId.equals(inviteeUserId)) {
            return;
        }

        AppUser inviter = appUserMapper.selectById(inviterUserId);
        if (inviter == null) {
            log.warn("邀请人不存在，跳过首单奖励 inviterUserId={} inviteeUserId={}",
                    inviterUserId, inviteeUserId);
            return;
        }

        ReferralRecord record = referralRecordMapper.selectByInviteeForUpdate(inviteeUserId);
        if (record == null) {
            referralRecordMapper.insertIgnore(inviterUserId, inviteeUserId);
            // 无论本次插入是否成功，都回查数据库中的权威行；并发创建时以唯一键胜出者为准。
            record = referralRecordMapper.selectByInviteeForUpdate(inviteeUserId);
        }
        if (record == null) {
            throw new IllegalStateException("邀请首单记录创建后未查询到 inviteeUserId=" + inviteeUserId);
        }
        if (COMPLETED.equals(record.getStatus()) || PAID.equals(record.getFirstOrderStatus())) {
            return;
        }

        Map<String, Object> config = referralConfigService.getConfig();
        long points = nonNegativeLong(config.get("firstOrderPoints"), 3L);
        long couponAmountYuan = nonNegativeLong(config.get("firstOrderCouponAmount"), 3L);
        long couponAmountFen = couponAmountYuan * 100L;

        pointsService.change(inviterUserId, "EARN", points, "referral", orderNo, "邀请好友首单奖励");
        couponService.issueReferralCoupon(inviterUserId, couponAmountFen);
        pointsService.change(inviteeUserId, "EARN", points, "referral", orderNo, "好友首单奖励");
        couponService.issueReferralCoupon(inviteeUserId, couponAmountFen);

        record.setStatus(COMPLETED);
        record.setFirstOrderStatus(PAID);
        referralRecordMapper.updateById(record);
        log.info("邀请首单奖励发放成功 inviterUserId={} inviteeUserId={} orderNo={} points={} couponAmountFen={}",
                inviterUserId, inviteeUserId, orderNo, points, couponAmountFen);
    }

    private long nonNegativeLong(Object value, long fallback) {
        if (value instanceof Number number) {
            long result = number.longValue();
            return result < 0 ? fallback : result;
        }
        if (value == null) {
            return fallback;
        }
        try {
            long result = Long.parseLong(String.valueOf(value).trim());
            return result < 0 ? fallback : result;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
