package com.wuling.marketing.service;

import com.wuling.marketing.entity.GrowthRecord;
import com.wuling.marketing.mapper.GrowthRecordMapper;
import com.wuling.user.mapper.AppUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 会员成长值（累计消费）累加。
 *
 * <p><b>口径</b>：成长值 = 累计消费金额（1 元 = 1 成长值），
 * 只累加「点单消费」订单的实付金额（分）。储值充值订单不发
 * {@code OrderPaidEvent}，因此天然不计成长值。
 *
 * <p><b>幂等</b>：{@code growth_record.order_no} 唯一索引作为闸门，
 * MQ 重复投递同一订单时抛 {@link DuplicateKeyException}，由消费端捕获后
 * 直接 ACK（不重复累加）。
 */
@Service
public class GrowthService {

    private static final Logger log = LoggerFactory.getLogger(GrowthService.class);

    private final AppUserMapper appUserMapper;
    private final GrowthRecordMapper growthRecordMapper;

    public GrowthService(AppUserMapper appUserMapper, GrowthRecordMapper growthRecordMapper) {
        this.appUserMapper = appUserMapper;
        this.growthRecordMapper = growthRecordMapper;
    }

    /**
     * 累加成长值并写流水（在同一事务内）。
     *
     * <p>流程：原子累加 app_user.total_spend → 读回累计值 → 写 growth_record。
     * 任一失败整体回滚，由 MQ 重试；order_no 唯一冲突说明已处理过，直接抛出让上层 ACK。
     *
     * @param userId     用户 ID
     * @param amountFen  本次实付金额（分，正数）
     * @param orderNo    订单号（幂等键）
     * @return 累加后的累计消费（分）
     */
    @Transactional(rollbackFor = Exception.class)
    public long addSpend(Long userId, long amountFen, String orderNo) {
        if (userId == null || amountFen <= 0 || orderNo == null || orderNo.isBlank()) {
            log.warn("成长值累加参数非法 userId={} amount={} orderNo={}", userId, amountFen, orderNo);
            throw new IllegalArgumentException("成长值累加参数非法");
        }

        int updated = appUserMapper.addTotalSpend(userId, amountFen);
        if (updated == 0) {
            throw new IllegalStateException("成长值累加失败，用户不存在 userId=" + userId);
        }

        Long totalAfter = appUserMapper.getTotalSpend(userId);
        long total = totalAfter == null ? amountFen : totalAfter;

        GrowthRecord record = new GrowthRecord();
        record.setUserId(userId);
        record.setOrderNo(orderNo);
        record.setAmount(amountFen);
        record.setTotalAfter(total);
        record.setSource("ORDER_PAID");
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());
        growthRecordMapper.insert(record);

        log.info("成长值累加成功 userId={} orderNo={} amount={} total={}",
                userId, orderNo, amountFen, total);
        return total;
    }
}
