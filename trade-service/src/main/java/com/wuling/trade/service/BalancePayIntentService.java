package com.wuling.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.trade.entity.BalancePayIntent;
import com.wuling.trade.mapper.BalancePayIntentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 余额支付意图的读写（独立事务，供「扣款-落库」补偿对账）。
 *
 * <p><b>关键点：{@code REQUIRES_NEW}</b> —— 意图记录必须在「扣款前」用独立事务
 * 提交。若与主事务共用事务，主事务回滚会把意图一并回滚，补偿任务就无从发现
 * 「已扣款但订单未支付」的悬挂单。只有独立提交，意图才能在回滚后依然留存。
 */
@Service
public class BalancePayIntentService {

    private static final Logger log = LoggerFactory.getLogger(BalancePayIntentService.class);

    private final BalancePayIntentMapper intentMapper;

    public BalancePayIntentService(BalancePayIntentMapper intentMapper) {
        this.intentMapper = intentMapper;
    }

    /**
     * 扣款前登记支付意图（独立事务提交）。
     *
     * <p><b>幂等</b>：同订单已有未完结意图时，视为重复提交，直接复用；
     * NO_DEBIT 表示未发生扣款，可按原业务号重新登记；已 DONE / COMPENSATED 不可再支付，
     * 这里防御性地抛错，避免异常流程绕进支付。
     *
     * @param orderNo 订单号
     * @param userId  用户 ID
     * @param amount  扣款金额（分）
     * @return 意图（含数据库主键）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public BalancePayIntent register(String orderNo, Long userId, long amount) {
        BalancePayIntent intent = new BalancePayIntent();
        intent.setOrderNo(orderNo);
        intent.setUserId(userId);
        intent.setAmount(amount);
        intent.setStatus(BalancePayIntent.STATUS_PENDING);
        try {
            intentMapper.insert(intent);
        } catch (DuplicateKeyException duplicate) {
            // 已确认未扣款时只重开原意图；真实回冲的 COMPENSATED 不得重开。
            BalancePayIntent existing = intentMapper.selectOne(new LambdaQueryWrapper<BalancePayIntent>()
                    .eq(BalancePayIntent::getOrderNo, orderNo)
                    .last("limit 1"));
            if (existing != null && BalancePayIntent.STATUS_NO_DEBIT.equals(existing.getStatus())
                    && java.util.Objects.equals(existing.getUserId(), userId)
                    && java.util.Objects.equals(existing.getAmount(), amount)) {
                BalancePayIntent retry = new BalancePayIntent();
                retry.setStatus(BalancePayIntent.STATUS_PENDING);
                if (intentMapper.update(retry, new LambdaQueryWrapper<BalancePayIntent>()
                        .eq(BalancePayIntent::getId, existing.getId())
                        .eq(BalancePayIntent::getStatus, BalancePayIntent.STATUS_NO_DEBIT)) == 1) {
                    existing.setStatus(BalancePayIntent.STATUS_PENDING);
                    return existing;
                }
            }
            if (existing != null && BalancePayIntent.STATUS_PENDING.equals(existing.getStatus())) {
                log.info("余额支付意图已存在（复用） orderNo={} intentId={}", orderNo, existing.getId());
                return existing;
            }
            throw new BusinessException(ResultCode.BAD_REQUEST, "该订单已处理，请勿重复支付");
        }
        log.info("登记余额支付意图 orderNo={} userId={} amount={} intentId={}",
                orderNo, userId, amount, intent.getId());
        return intent;
    }

    /**
     * 标记意图为「订单已支付」（正常终态）。
     *
     * <p>在扣款 + 订单落库都成功后调用；用条件更新（PENDING → DONE）保证幂等。
     */
    /**
     * 标记意图为「订单已支付」。纳入主事务：随订单/支付单一起提交，
     * 避免 REQUIRES_NEW 导致主事务回滚前就提前把意图置为 DONE。
     * 条件更新（PENDING → DONE）保证幂等。
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void markDone(String orderNo) {
        BalancePayIntent update = new BalancePayIntent();
        update.setStatus(BalancePayIntent.STATUS_DONE);
        intentMapper.update(update,
                new LambdaQueryWrapper<BalancePayIntent>()
                        .eq(BalancePayIntent::getOrderNo, orderNo)
                        .eq(BalancePayIntent::getStatus, BalancePayIntent.STATUS_PENDING));
    }

    /**
     * 标记意图为「已回冲」（补偿任务发现悬挂单并退回余额后调用）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markCompensated(String orderNo) {
        BalancePayIntent intent = intentMapper.selectOne(new LambdaQueryWrapper<BalancePayIntent>()
                .eq(BalancePayIntent::getOrderNo, orderNo)
                .last("limit 1"));
        if (intent == null) {
            return;
        }
        BalancePayIntent update = new BalancePayIntent();
        update.setId(intent.getId());
        update.setStatus(BalancePayIntent.STATUS_COMPENSATED);
        intentMapper.updateById(update);
    }
}
