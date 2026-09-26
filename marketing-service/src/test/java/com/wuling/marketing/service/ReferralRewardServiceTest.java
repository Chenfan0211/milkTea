package com.wuling.marketing.service;

import com.wuling.marketing.entity.ReferralRecord;
import com.wuling.marketing.mapper.ReferralRecordMapper;
import com.wuling.user.entity.AppUser;
import com.wuling.user.mapper.AppUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 邀请首单发奖的资格判定与幂等边界。 */
class ReferralRewardServiceTest {

    @Mock
    private AppUserMapper appUserMapper;
    @Mock
    private ReferralRecordMapper referralRecordMapper;
    @Mock
    private PointsService pointsService;
    @Mock
    private CouponService couponService;
    @Mock
    private ReferralConfigService referralConfigService;

    private ReferralRewardService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new ReferralRewardService(
                appUserMapper, referralRecordMapper, pointsService, couponService, referralConfigService);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("firstOrderPoints", 3);
        config.put("firstOrderCouponAmount", 3);
        when(referralConfigService.getConfig()).thenReturn(config);
    }

    private AppUser user(long id, Long referrerId) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setReferrerId(referrerId);
        return user;
    }

    @Test
    @DisplayName("无邀请人：不创建首单记录，也不发奖")
    void skipsWhenNoReferrer() {
        when(appUserMapper.selectById(20L)).thenReturn(user(20L, null));

        service.rewardFirstOrder(20L, "ORDER-1");

        verify(pointsService, never()).change(any(), any(), anyLong(), any(), any(), any());
        verify(couponService, never()).issueReferralCoupon(any(), anyLong());
        verify(referralRecordMapper, never()).insertIgnore(any(), any());
    }

    @Test
    @DisplayName("自己邀请自己：不创建首单记录，也不发奖")
    void skipsSelfReferral() {
        when(appUserMapper.selectById(20L)).thenReturn(user(20L, 20L));

        service.rewardFirstOrder(20L, "ORDER-1");

        verify(pointsService, never()).change(any(), any(), anyLong(), any(), any(), any());
        verify(referralRecordMapper, never()).insertIgnore(any(), any());
    }

    @Test
    @DisplayName("首次支付：邀请人与被邀请人各发 3 时光币和 3 元券，并把记录置为已完成")
    void rewardsBothSidesOnFirstPaidOrder() {
        when(appUserMapper.selectById(20L)).thenReturn(user(20L, 10L));
        when(appUserMapper.selectById(10L)).thenReturn(user(10L, null));
        ReferralRecord pending = new ReferralRecord();
        pending.setId(1L);
        pending.setInviterUserId(10L);
        pending.setInviteeUserId(20L);
        pending.setStatus("PENDING");
        pending.setFirstOrderStatus("UNPAID");
        when(referralRecordMapper.selectByInviteeForUpdate(20L)).thenReturn(null, pending);
        when(referralRecordMapper.insertIgnore(10L, 20L)).thenReturn(1);

        service.rewardFirstOrder(20L, "ORDER-1");

        verify(pointsService).change(10L, "EARN", 3L, "referral", "ORDER-1", "邀请好友首单奖励");
        verify(couponService).issueReferralCoupon(10L, 300L);
        verify(pointsService).change(20L, "EARN", 3L, "referral", "ORDER-1", "好友首单奖励");
        verify(couponService).issueReferralCoupon(20L, 300L);
        assertEquals("COMPLETED", pending.getStatus());
        assertEquals("PAID", pending.getFirstOrderStatus());
        verify(referralRecordMapper).updateById((ReferralRecord) pending);
    }

    @Test
    @DisplayName("重复消息：已完成记录直接跳过，不重复发时光币或券")
    void skipsCompletedRecord() {
        when(appUserMapper.selectById(20L)).thenReturn(user(20L, 10L));
        when(appUserMapper.selectById(10L)).thenReturn(user(10L, null));
        ReferralRecord completed = new ReferralRecord();
        completed.setInviterUserId(10L);
        completed.setInviteeUserId(20L);
        completed.setStatus("COMPLETED");
        completed.setFirstOrderStatus("PAID");
        when(referralRecordMapper.selectByInviteeForUpdate(20L)).thenReturn(completed);

        service.rewardFirstOrder(20L, "ORDER-1");

        verify(pointsService, never()).change(any(), any(), anyLong(), any(), any(), any());
        verify(couponService, never()).issueReferralCoupon(any(), anyLong());
        verify(referralRecordMapper, never()).updateById(any(ReferralRecord.class));
    }

    @Test
    @DisplayName("并发建记录：唯一键命中后回查已有 PENDING 记录，只发放一次")
    void reusesRecordCreatedByConcurrentConsumer() {
        when(appUserMapper.selectById(20L)).thenReturn(user(20L, 10L));
        when(appUserMapper.selectById(10L)).thenReturn(user(10L, null));
        ReferralRecord pending = new ReferralRecord();
        pending.setId(2L);
        pending.setInviterUserId(10L);
        pending.setInviteeUserId(20L);
        pending.setStatus("PENDING");
        pending.setFirstOrderStatus("UNPAID");
        when(referralRecordMapper.selectByInviteeForUpdate(20L)).thenReturn(null, pending);
        when(referralRecordMapper.insertIgnore(10L, 20L)).thenReturn(0);

        service.rewardFirstOrder(20L, "ORDER-1");

        verify(pointsService).change(10L, "EARN", 3L, "referral", "ORDER-1", "邀请好友首单奖励");
        verify(pointsService).change(20L, "EARN", 3L, "referral", "ORDER-1", "好友首单奖励");
        verify(referralRecordMapper).updateById(pending);
    }
}