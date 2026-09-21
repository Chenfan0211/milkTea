package com.wuling.marketing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.marketing.entity.ExchangeOrder;
import com.wuling.marketing.mapper.ExchangeOrderMapper;
import com.wuling.marketing.entity.PointsEarningRule;
import com.wuling.marketing.entity.PointsProduct;
import com.wuling.marketing.entity.PointsRecord;
import com.wuling.marketing.entity.PointsSignin;
import com.wuling.marketing.entity.PointsSigninRule;
import com.wuling.marketing.mapper.PointsEarningRuleMapper;
import com.wuling.marketing.mapper.PointsProductMapper;
import com.wuling.marketing.mapper.PointsRecordMapper;
import com.wuling.marketing.mapper.PointsSigninMapper;
import com.wuling.marketing.mapper.PointsSigninRuleMapper;
import com.wuling.user.entity.AppUser;
import com.wuling.user.mapper.AppUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/** 时光币（积分）：获取 / 签到 / 兑换 */
@Service
public class PointsService {

    public static final String PENDING = "PENDING";
    public static final String VERIFIED = "VERIFIED";

    private final PointsProductMapper pointsProductMapper;
    private final PointsRecordMapper pointsRecordMapper;
    private final PointsSigninMapper pointsSigninMapper;
    private final PointsSigninRuleMapper pointsSigninRuleMapper;
    private final PointsEarningRuleMapper pointsEarningRuleMapper;
    private final AppUserMapper appUserMapper;
    private final ExchangeOrderMapper exchangeOrderMapper;

    public PointsService(PointsProductMapper pointsProductMapper,
                         PointsRecordMapper pointsRecordMapper,
                         PointsSigninMapper pointsSigninMapper,
                         PointsSigninRuleMapper pointsSigninRuleMapper,
                         PointsEarningRuleMapper pointsEarningRuleMapper,
                         AppUserMapper appUserMapper,
                         ExchangeOrderMapper exchangeOrderMapper) {
        this.pointsProductMapper = pointsProductMapper;
        this.pointsRecordMapper = pointsRecordMapper;
        this.pointsSigninMapper = pointsSigninMapper;
        this.pointsSigninRuleMapper = pointsSigninRuleMapper;
        this.pointsEarningRuleMapper = pointsEarningRuleMapper;
        this.appUserMapper = appUserMapper;
        this.exchangeOrderMapper = exchangeOrderMapper;
    }

    public List<PointsProduct> listProducts(String category) {
        return pointsProductMapper.selectList(new LambdaQueryWrapper<PointsProduct>()
                .eq(PointsProduct::getStatus, "enabled")
                .orderByAsc(PointsProduct::getId));
    }

    public List<PointsEarningRule> earningRules() {
        return pointsEarningRuleMapper.selectList(new LambdaQueryWrapper<PointsEarningRule>()
                .orderByAsc(PointsEarningRule::getSort).orderByAsc(PointsEarningRule::getId));
    }

    public List<PointsRecord> records(Long userId) {
        return pointsRecordMapper.selectList(new LambdaQueryWrapper<PointsRecord>()
                .eq(PointsRecord::getUserId, userId)
                .orderByDesc(PointsRecord::getId));
    }

    /** 增减时光币（唯一入口，保证余额与流水一致） */
    @Transactional(rollbackFor = Exception.class)
    public long change(Long userId, String type, long amount, String source, String orderNo, String remark) {
        AppUser user = appUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        long current = user.getPoints() == null ? 0L : user.getPoints();
        long next = current + amount;
        if (next < 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "时光币不足");
        }
        AppUser patch = new AppUser();
        patch.setId(userId);
        patch.setPoints(next);
        appUserMapper.updateById(patch);

        PointsRecord record = new PointsRecord();
        record.setUserId(userId);
        record.setType(type);
        record.setAmount(amount);
        record.setBalanceAfter(next);
        record.setSource(source);
        record.setOrderNo(orderNo);
        record.setRemark(remark);
        pointsRecordMapper.insert(record);
        return next;
    }

    /** 每日签到（幂等：同一天重复签到被拒绝） */
    @Transactional(rollbackFor = Exception.class)
    public long signIn(Long userId) {
        LocalDate today = LocalDate.now();
        Long exists = pointsSigninMapper.selectCount(new LambdaQueryWrapper<PointsSignin>()
                .eq(PointsSignin::getUserId, userId)
                .eq(PointsSignin::getSignDate, today));
        if (exists != null && exists > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "今日已签到");
        }
        PointsSignin signin = new PointsSignin();
        signin.setUserId(userId);
        signin.setSignDate(today);
        pointsSigninMapper.insert(signin);

        PointsSigninRule rule = pointsSigninRuleMapper.selectList(
                new LambdaQueryWrapper<PointsSigninRule>().orderByAsc(PointsSigninRule::getId))
                .stream().findFirst().orElse(null);
        long daily = rule == null || rule.getDaily() == null ? 1L : rule.getDaily();
        return change(userId, "EARN", daily, "signin", null, "每日签到");
    }

    /** 积分兑换（扣库存 + 扣币 + 生成自提码） */
    @Transactional(rollbackFor = Exception.class)
    public ExchangeOrder exchange(Long userId, Long productId) {
        PointsProduct product = pointsProductMapper.selectById(productId);
        if (product == null || !"enabled".equals(product.getStatus())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "兑换商品不存在");
        }
        if (product.getStock() != null && product.getStock() <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "库存不足");
        }
        long points = product.getPoints() == null ? 0L : product.getPoints();
        change(userId, "CONSUME", -points, "exchange", null, "兑换 " + product.getName());

        PointsProduct patch = new PointsProduct();
        patch.setId(product.getId());
        patch.setStock(product.getStock() - 1);
        pointsProductMapper.updateById(patch);

        // 落库兑换单：自提码与订单一一对应，供门店核销（type=EXCHANGE）
        String pickupCode = nextExchangeNo();
        ExchangeOrder order = new ExchangeOrder();
        order.setExchangeNo(pickupCode);
        order.setUserId(userId);
        order.setPointsProductId(product.getId());
        order.setPoints(points);
        order.setPickupCode(pickupCode);
        order.setStatus(PENDING);
        exchangeOrderMapper.insert(order);
        return order;
    }

    /** 我的兑换记录 */
    public List<ExchangeOrder> exchangeOrders(Long userId) {
        return exchangeOrderMapper.selectList(new LambdaQueryWrapper<ExchangeOrder>()
                .eq(ExchangeOrder::getUserId, userId)
                .orderByDesc(ExchangeOrder::getId));
    }

    /** 核销兑换单（幂等：已核销的重复核销会被拒绝） */
    @Transactional(rollbackFor = Exception.class)
    public ExchangeOrder verifyExchange(String pickupCode) {
        ExchangeOrder order = exchangeOrderMapper.selectOne(new LambdaQueryWrapper<ExchangeOrder>()
                .eq(ExchangeOrder::getPickupCode, pickupCode)
                .last("limit 1 for update"));
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "兑换码无效");
        }
        if (VERIFIED.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "兑换码已核销，请勿重复核销");
        }
        order.setStatus(VERIFIED);
        exchangeOrderMapper.updateById(order);
        return order;
    }

    /** 自提码：CZ + 时间戳，保证唯一 */
    private String nextExchangeNo() {
        return "CZ" + System.currentTimeMillis() % 100000000000000L;
    }
}


