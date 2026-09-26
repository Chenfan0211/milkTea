package com.wuling.marketing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wuling.common.api.PageResult;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.marketing.entity.GiftCard;
import com.wuling.marketing.entity.GiftCardDenomination;
import com.wuling.marketing.entity.GiftCardOrder;
import com.wuling.marketing.entity.GiftCardRefund;
import com.wuling.marketing.mapper.GiftCardDenominationMapper;
import com.wuling.marketing.mapper.GiftCardMapper;
import com.wuling.marketing.mapper.GiftCardOrderMapper;
import com.wuling.marketing.mapper.GiftCardRefundMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** 礼品卡：一单一卡、支付后发卡、未核销退款、订单绑定核销。 */
@Service
public class GiftCardService {

    private static final Logger log = LoggerFactory.getLogger(GiftCardService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final String PAY_UNPAID = "UNPAID";
    private static final String PAY_PAID = "PAID";
    private static final String PAY_REFUNDED = "REFUNDED";
    private static final String STATUS_CREATED = "CREATED";
    private static final String STATUS_PAID = "PAID";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_CANCELED = "CANCELED";
    private static final String VERIFY_UNVERIFIED = "UNVERIFIED";
    private static final String REFUND_REFUNDING = "REFUNDING";
    private static final String REFUND_FAILED = "FAILED";
    private static final String REFUND_SUCCESS = "SUCCESS";

    private final GiftCardDenominationMapper denominationMapper;
    private final GiftCardMapper giftCardMapper;
    private final GiftCardOrderMapper orderMapper;
    private final GiftCardRefundMapper refundMapper;
    private final JdbcTemplate jdbcTemplate;
    private final long timeoutMinutes;

    public GiftCardService(GiftCardDenominationMapper denominationMapper,
                           GiftCardMapper giftCardMapper,
                           GiftCardOrderMapper orderMapper,
                           GiftCardRefundMapper refundMapper,
                           JdbcTemplate jdbcTemplate,
                           @org.springframework.beans.factory.annotation.Value("${app.gift-card.timeout-minutes:15}")
                           long timeoutMinutes) {
        this.denominationMapper = denominationMapper;
        this.giftCardMapper = giftCardMapper;
        this.orderMapper = orderMapper;
        this.refundMapper = refundMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.timeoutMinutes = timeoutMinutes > 0 ? timeoutMinutes : 15L;
    }

    public List<GiftCardDenomination> listDenominations() {
        return denominationMapper.selectList(new LambdaQueryWrapper<GiftCardDenomination>()
                .eq(GiftCardDenomination::getStatus, "enabled")
                .orderByAsc(GiftCardDenomination::getAmount));
    }

    /** 创建一笔只包含一个面额、一张卡的待支付订单。 */
    @Transactional(rollbackFor = Exception.class)
    public GiftCardOrder purchase(Long userId, Long denominationId) {
        if (userId == null || denominationId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "礼品卡参数不能为空");
        }
        GiftCardDenomination denom = denominationMapper.selectById(denominationId);
        if (denom == null || !"enabled".equals(denom.getStatus()) || isDeleted(denom.getDeleted())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "礼品卡面额不存在");
        }
        Long salePrice = denom.getSalePrice() == null ? denom.getAmount() : denom.getSalePrice();
        if (salePrice == null || salePrice <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "礼品卡售价异常");
        }

        GiftCardOrder order = new GiftCardOrder();
        order.setOrderNo(nextNo("GC"));
        order.setUserId(userId);
        order.setDenominationId(denominationId);
        order.setAmount(salePrice);
        order.setPayStatus(PAY_UNPAID);
        order.setStatus(STATUS_CREATED);
        order.setVerifyStatus(VERIFY_UNVERIFIED);
        order.setExpireTime(LocalDateTime.now().plusMinutes(timeoutMinutes));
        orderMapper.insert(order);
        return order;
    }

    public List<GiftCard> myCards(Long userId) {
        List<GiftCard> cards = giftCardMapper.selectList(new LambdaQueryWrapper<GiftCard>()
                .eq(GiftCard::getOwnerUserId, userId)
                .orderByDesc(GiftCard::getId));
        Map<Long, Map<String, Object>> metadata = loadDenominationMetadata(denominationIdsFromCards(cards));
        for (GiftCard card : cards) {
            applyDenominationMetadata(card, metadata.get(card.getDenominationId()));
        }
        return cards;
    }

    /** 我的礼品卡订单（分页）。 */
    public PageResult<GiftCardOrder> myOrders(Long userId, long current, long size) {
        Page<GiftCardOrder> page = orderMapper.selectPage(
                new Page<>(current, size),
                new LambdaQueryWrapper<GiftCardOrder>()
                        .eq(GiftCardOrder::getUserId, userId)
                        .orderByDesc(GiftCardOrder::getId));
        List<GiftCardOrder> records = page.getRecords();
        Map<Long, Map<String, Object>> metadata = loadDenominationMetadata(denominationIdsFromOrders(records));
        for (GiftCardOrder order : records) {
            Map<String, Object> row = metadata.get(order.getDenominationId());
            if (row != null) {
                order.setCardName(stringValue(row.get("card_name")));
                order.setCardImage(stringValue(row.get("card_image")));
                order.setGroupTitle(stringValue(row.get("group_title")));
                order.setSalePrice(longValue(row.get("sale_price")));
                // 不覆盖 amount：订单金额是实际支付售价，不是面值。
            }
            applyRefundSummary(order);
        }
        return PageResult.of(records, page.getCurrent(), page.getSize(), page.getTotal());
    }

    /** 支付后轮询订单，只允许查询本人订单。 */
    public GiftCardOrder orderView(Long userId, String orderNo) {
        GiftCardOrder order = requireByOrderNo(orderNo);
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权查看该礼品卡订单");
        }
        return enrich(order);
    }

    public GiftCardOrder requireByOrderNo(String orderNo) {
        if (!StringUtils.hasText(orderNo)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单号不能为空");
        }
        GiftCardOrder order = orderMapper.selectOne(new LambdaQueryWrapper<GiftCardOrder>()
                .eq(GiftCardOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "礼品卡订单不存在");
        }
        applyRefundSummary(order);
        return order;
    }

    /** 取消待支付订单；已支付订单只能走真实退款接口。 */
    @Transactional(rollbackFor = Exception.class)
    public GiftCardOrder cancelOrder(Long userId, String orderNo) {
        GiftCardOrder order = requireOwnedOrder(userId, orderNo);
        if (STATUS_CANCELED.equals(order.getStatus())) {
            return order;
        }
        if (!PAY_UNPAID.equals(order.getPayStatus()) || !STATUS_CREATED.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "当前订单状态不可取消");
        }
        GiftCardOrder update = new GiftCardOrder();
        update.setStatus(STATUS_CANCELED);
        update.setCancelType("pending");
        int affected = orderMapper.update(update, new LambdaQueryWrapper<GiftCardOrder>()
                .eq(GiftCardOrder::getId, order.getId())
                .eq(GiftCardOrder::getPayStatus, PAY_UNPAID)
                .eq(GiftCardOrder::getStatus, STATUS_CREATED));
        if (affected == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单状态已变化，请刷新后重试");
        }
        order.setStatus(STATUS_CANCELED);
        order.setCancelType("pending");
        return order;
    }

    /**
     * 微信支付成功入账并发一张绑定订单的卡。
     *
     * <p>若订单本地已取消/超时，仍以微信资金事实为准恢复并补发卡。
     */
    @Transactional(rollbackFor = Exception.class)
    public GiftCardOrder settle(String orderNo, String transactionId,
                                String payerOpenid, Long callbackAmount) {
        GiftCardOrder order = requireByOrderNoForUpdate(orderNo);
        if (callbackAmount == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "微信支付回调金额不能为空");
        }
        if (!StringUtils.hasText(transactionId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "微信支付交易号不能为空");
        }
        String wxTransactionId = transactionId.trim();
        if (!order.getAmount().equals(callbackAmount)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "回调金额与订单金额不一致");
        }
        if (PAY_REFUNDED.equals(order.getPayStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单处于退款状态，不能重复入账");
        }
        if (PAY_PAID.equals(order.getPayStatus())) {
            if (!StringUtils.hasText(order.getTransactionId())) {
                GiftCardOrder repair = new GiftCardOrder();
                repair.setTransactionId(wxTransactionId);
                int affected = orderMapper.update(repair, new LambdaUpdateWrapper<GiftCardOrder>()
                        .eq(GiftCardOrder::getId, order.getId())
                        .eq(GiftCardOrder::getPayStatus, PAY_PAID)
                        .and(wrapper -> wrapper.isNull(GiftCardOrder::getTransactionId)
                                .or().eq(GiftCardOrder::getTransactionId, "")));
                if (affected > 0) {
                    order.setTransactionId(wxTransactionId);
                } else {
                    GiftCardOrder latest = requireByOrderNo(orderNo);
                    if (!wxTransactionId.equals(latest.getTransactionId())) {
                        throw new BusinessException(ResultCode.BAD_REQUEST, "重复支付回调交易号不一致");
                    }
                    order = latest;
                }
            } else if (!wxTransactionId.equals(order.getTransactionId().trim())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "重复支付回调交易号不一致");
            }
            ensureCard(order);
            return enrich(order);
        }

        LocalDateTime payTime = LocalDateTime.now();
        int affected = orderMapper.update(null, new LambdaUpdateWrapper<GiftCardOrder>()
                .eq(GiftCardOrder::getId, order.getId())
                .eq(GiftCardOrder::getPayStatus, PAY_UNPAID)
                .set(GiftCardOrder::getPayStatus, PAY_PAID)
                .set(GiftCardOrder::getStatus, STATUS_PAID)
                .set(GiftCardOrder::getPayTime, payTime)
                .set(GiftCardOrder::getTransactionId, wxTransactionId)
                .set(GiftCardOrder::getCancelType, null));
        if (affected == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单状态不可支付");
        }
        order.setPayStatus(PAY_PAID);
        order.setStatus(STATUS_PAID);
        order.setPayTime(payTime);
        order.setTransactionId(wxTransactionId);
        order.setCancelType(null);
        ensureCard(order);
        log.info("gift card settled orderNo={} transactionId={}", orderNo, wxTransactionId);
        return enrich(order);
    }

    private void ensureCard(GiftCardOrder order) {
        GiftCard existing = giftCardMapper.selectOne(new LambdaQueryWrapper<GiftCard>()
                .eq(GiftCard::getOrderId, order.getId())
                .last("limit 1"));
        if (existing != null) {
            return;
        }
        GiftCard card = new GiftCard();
        card.setCardNo(nextNo("CARD"));
        card.setDenominationId(order.getDenominationId());
        card.setOrderId(order.getId());
        card.setStatus("ACTIVE");
        card.setOwnerUserId(order.getUserId());
        try {
            giftCardMapper.insert(card);
        } catch (DuplicateKeyException e) {
            log.info("gift card already issued by concurrent callback orderNo={}", order.getOrderNo());
        }
    }

    /** 核销：只核销本订单绑定的卡，避免同面额多单串卡。 */
    @Transactional(rollbackFor = Exception.class)
    public GiftCardOrder verifyOrder(Long userId, String orderNo) {
        GiftCardOrder order = requireOwnedOrderForUpdate(userId, orderNo);
        if (PAY_REFUNDED.equals(order.getPayStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单已退款，无法核销");
        }
        if (!PAY_PAID.equals(order.getPayStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单未支付，无法核销");
        }
        if (STATUS_COMPLETED.equals(order.getStatus()) || "VERIFIED".equals(order.getVerifyStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单已核销");
        }
        if (hasRefundingRecord(orderNo)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "退款处理中，不能核销");
        }

        GiftCardOrder update = new GiftCardOrder();
        update.setStatus(STATUS_COMPLETED);
        update.setVerifyStatus("VERIFIED");
        update.setVerifyTime(LocalDateTime.now());
        int affected = orderMapper.update(update, new LambdaQueryWrapper<GiftCardOrder>()
                .eq(GiftCardOrder::getId, order.getId())
                .eq(GiftCardOrder::getPayStatus, PAY_PAID)
                .eq(GiftCardOrder::getStatus, STATUS_PAID)
                .eq(GiftCardOrder::getVerifyStatus, VERIFY_UNVERIFIED));
        if (affected == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单状态已变化，无法核销");
        }

        GiftCard card = giftCardMapper.selectOne(new LambdaQueryWrapper<GiftCard>()
                .eq(GiftCard::getOrderId, order.getId())
                .last("limit 1"));
        if (card == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单未绑定礼品卡");
        }
        if (!"ACTIVE".equals(card.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "礼品卡不可用");
        }
        int cardAffected = giftCardMapper.update(null, new LambdaUpdateWrapper<GiftCard>()
                .eq(GiftCard::getId, card.getId())
                .eq(GiftCard::getOrderId, order.getId())
                .eq(GiftCard::getStatus, "ACTIVE")
                .set(GiftCard::getStatus, "USED"));
        if (cardAffected == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "礼品卡状态已变化，无法核销");
        }

        order.setStatus(STATUS_COMPLETED);
        order.setVerifyStatus("VERIFIED");
        order.setVerifyTime(update.getVerifyTime());
        card.setStatus("USED");
        return order;
    }

    /** 受理退款：锁定订单并生成新的退款单，失败后重试会生成新记录。 */
    @Transactional(rollbackFor = Exception.class)
    public GiftCardRefund refundBegin(String orderNo, String reason) {
        GiftCardOrder order = requireByOrderNoForUpdate(orderNo);
        if (!PAY_PAID.equals(order.getPayStatus()) || !STATUS_PAID.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "当前订单状态不可退款");
        }
        if (!VERIFY_UNVERIFIED.equals(order.getVerifyStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "已核销订单不可退款");
        }
        if (hasRefundingRecord(orderNo)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "退款处理中，请勿重复申请");
        }
        if (!StringUtils.hasText(order.getTransactionId())
                || order.getTransactionId().startsWith("DEMO-")) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "历史模拟支付订单请转人工退款");
        }

        GiftCardRefund refund = new GiftCardRefund();
        refund.setRefundNo(nextNo("GR"));
        refund.setOrderNo(orderNo);
        refund.setAmount(order.getAmount());
        refund.setReason(StringUtils.hasText(reason) ? reason.trim() : "用户申请退款");
        refund.setStatus(REFUND_REFUNDING);
        refundMapper.insert(refund);

        int affected = orderMapper.update(null, new LambdaUpdateWrapper<GiftCardOrder>()
                .eq(GiftCardOrder::getId, order.getId())
                .eq(GiftCardOrder::getPayStatus, PAY_PAID)
                .eq(GiftCardOrder::getStatus, STATUS_PAID)
                .eq(GiftCardOrder::getVerifyStatus, VERIFY_UNVERIFIED)
                .set(GiftCardOrder::getCancelType, "refund")
                .set(GiftCardOrder::getRefundAmount, order.getAmount()));
        if (affected == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单状态已变化，请刷新后重试");
        }
        order.setCancelType("refund");
        order.setRefundAmount(order.getAmount());
        return refund;
    }

    /** 微信退款终态成功：订单置已退款，绑定卡作废。 */
    @Transactional(rollbackFor = Exception.class)
    public GiftCardRefund refundConfirm(String orderNo, String refundNo, String wxRefundId) {
        GiftCardOrder order = requireByOrderNoForUpdate(orderNo);
        GiftCardRefund refund = requireRefundForUpdate(orderNo, refundNo);
        if (STATUS_COMPLETED.equals(order.getStatus())
                || "VERIFIED".equals(order.getVerifyStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "已核销订单不可退款");
        }
        if (REFUND_SUCCESS.equals(refund.getStatus())) {
            return refund;
        }
        if (!REFUND_REFUNDING.equals(refund.getStatus()) && !REFUND_FAILED.equals(refund.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "退款单状态不可确认成功");
        }
        if (!PAY_PAID.equals(order.getPayStatus())
                && !PAY_REFUNDED.equals(order.getPayStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "原订单支付状态异常，不能确认退款");
        }
        if (!PAY_REFUNDED.equals(order.getPayStatus()) || !STATUS_CANCELED.equals(order.getStatus())) {
            int orderAffected = orderMapper.update(null, new LambdaUpdateWrapper<GiftCardOrder>()
                    .eq(GiftCardOrder::getId, order.getId())
                    .in(GiftCardOrder::getPayStatus, PAY_PAID, PAY_REFUNDED)
                    .in(GiftCardOrder::getStatus, STATUS_PAID, STATUS_CANCELED)
                    .eq(GiftCardOrder::getVerifyStatus, VERIFY_UNVERIFIED)
                    .set(GiftCardOrder::getPayStatus, PAY_REFUNDED)
                    .set(GiftCardOrder::getStatus, STATUS_CANCELED)
                    .set(GiftCardOrder::getCancelType, "refund")
                    .set(GiftCardOrder::getRefundAmount, refund.getAmount()));
            if (orderAffected == 0) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "原订单状态已变化，不能确认退款");
            }
        }

        LocalDateTime finishedTime = LocalDateTime.now();
        int refundAffected = refundMapper.update(null, new LambdaUpdateWrapper<GiftCardRefund>()
                .eq(GiftCardRefund::getId, refund.getId())
                .in(GiftCardRefund::getStatus, REFUND_REFUNDING, REFUND_FAILED)
                .set(GiftCardRefund::getStatus, REFUND_SUCCESS)
                .set(GiftCardRefund::getWxRefundId, wxRefundId)
                .set(GiftCardRefund::getFinishedTime, finishedTime)
                .set(GiftCardRefund::getFailReason, null));
        if (refundAffected == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "退款单状态已变化，不能确认成功");
        }

        GiftCard card = giftCardMapper.selectOne(new LambdaQueryWrapper<GiftCard>()
                .eq(GiftCard::getOrderId, order.getId())
                .last("limit 1"));
        if (card != null && !"VOID".equals(card.getStatus())) {
            int cardAffected = giftCardMapper.update(null, new LambdaUpdateWrapper<GiftCard>()
                    .eq(GiftCard::getId, card.getId())
                    .eq(GiftCard::getOrderId, order.getId())
                    .eq(GiftCard::getStatus, "ACTIVE")
                    .set(GiftCard::getStatus, "VOID"));
            if (cardAffected == 0) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "礼品卡状态已变化，不能作废");
            }
            card.setStatus("VOID");
        }
        refund.setStatus(REFUND_SUCCESS);
        refund.setWxRefundId(wxRefundId);
        refund.setFinishedTime(finishedTime);
        refund.setFailReason(null);
        order.setPayStatus(PAY_REFUNDED);
        order.setStatus(STATUS_CANCELED);
        order.setRefundAmount(refund.getAmount());
        log.info("gift card refund success orderNo={} refundNo={}", orderNo, refundNo);
        return refund;
    }

    /** 微信退款失败：退款单失败，订单恢复可重试。 */
    @Transactional(rollbackFor = Exception.class)
    public GiftCardRefund refundFail(String orderNo, String refundNo, String failReason) {
        GiftCardOrder order = requireByOrderNoForUpdate(orderNo);
        GiftCardRefund refund = requireRefundForUpdate(orderNo, refundNo);
        if (REFUND_SUCCESS.equals(refund.getStatus())) {
            return refund;
        }
        if (REFUND_FAILED.equals(refund.getStatus())) {
            return refund;
        }
        String reason = StringUtils.hasText(failReason) ? failReason : "微信退款失败";
        LocalDateTime finishedTime = LocalDateTime.now();
        int refundAffected = refundMapper.update(null, new LambdaUpdateWrapper<GiftCardRefund>()
                .eq(GiftCardRefund::getId, refund.getId())
                .eq(GiftCardRefund::getStatus, REFUND_REFUNDING)
                .set(GiftCardRefund::getStatus, REFUND_FAILED)
                .set(GiftCardRefund::getFailReason, reason)
                .set(GiftCardRefund::getFinishedTime, finishedTime));
        if (refundAffected == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "退款单状态已变化，不能标记失败");
        }

        int orderAffected = orderMapper.update(null, new LambdaUpdateWrapper<GiftCardOrder>()
                .eq(GiftCardOrder::getId, order.getId())
                .eq(GiftCardOrder::getPayStatus, PAY_PAID)
                .eq(GiftCardOrder::getStatus, STATUS_PAID)
                .eq(GiftCardOrder::getVerifyStatus, VERIFY_UNVERIFIED)
                .set(GiftCardOrder::getCancelType, null)
                .set(GiftCardOrder::getRefundAmount, 0L));
        if (orderAffected == 0) {
            log.info("gift card refund failed but order no longer needs auxiliary cleanup "
                    + "orderNo={} refundNo={}", orderNo, refundNo);
        } else {
            order.setCancelType(null);
            order.setRefundAmount(0L);
        }
        refund.setStatus(REFUND_FAILED);
        refund.setFailReason(reason);
        refund.setFinishedTime(finishedTime);
        return refund;
    }

    /** 每分钟关闭超过 15 分钟仍未支付的订单；迟到成功回调仍可恢复。 */
    @Scheduled(fixedDelayString = "${app.gift-card.close-expired-ms:60000}")
    @Transactional(rollbackFor = Exception.class)
    public int closeExpiredOrders() {
        GiftCardOrder update = new GiftCardOrder();
        update.setStatus(STATUS_CANCELED);
        update.setCancelType("timeout");
        return orderMapper.update(update, new LambdaQueryWrapper<GiftCardOrder>()
                .eq(GiftCardOrder::getPayStatus, PAY_UNPAID)
                .eq(GiftCardOrder::getStatus, STATUS_CREATED)
                .le(GiftCardOrder::getExpireTime, LocalDateTime.now()));
    }

    public GiftCardRefund requireRefund(String orderNo, String refundNo) {
        if (!StringUtils.hasText(refundNo)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "退款单号不能为空");
        }
        GiftCardRefund refund = refundMapper.selectOne(new LambdaQueryWrapper<GiftCardRefund>()
                .eq(GiftCardRefund::getRefundNo, refundNo)
                .eq(GiftCardRefund::getOrderNo, orderNo));
        if (refund == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "礼品卡退款单不存在");
        }
        return refund;
    }

    private GiftCardOrder requireByOrderNoForUpdate(String orderNo) {
        if (!StringUtils.hasText(orderNo)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单号不能为空");
        }
        GiftCardOrder order = orderMapper.selectOne(new LambdaQueryWrapper<GiftCardOrder>()
                .eq(GiftCardOrder::getOrderNo, orderNo)
                .last("limit 1 for update"));
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "礼品卡订单不存在");
        }
        return order;
    }

    private GiftCardOrder requireOwnedOrderForUpdate(Long userId, String orderNo) {
        if (!StringUtils.hasText(orderNo)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单号不能为空");
        }
        GiftCardOrder order = orderMapper.selectOne(new LambdaQueryWrapper<GiftCardOrder>()
                .eq(GiftCardOrder::getOrderNo, orderNo)
                .eq(GiftCardOrder::getUserId, userId)
                .last("limit 1 for update"));
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "礼品卡订单不存在");
        }
        return order;
    }

    private GiftCardRefund requireRefundForUpdate(String orderNo, String refundNo) {
        if (!StringUtils.hasText(refundNo)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "退款单号不能为空");
        }
        GiftCardRefund refund = refundMapper.selectOne(new LambdaQueryWrapper<GiftCardRefund>()
                .eq(GiftCardRefund::getRefundNo, refundNo)
                .eq(GiftCardRefund::getOrderNo, orderNo)
                .last("limit 1 for update"));
        if (refund == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "礼品卡退款单不存在");
        }
        return refund;
    }

    private boolean hasRefundingRecord(String orderNo) {
        Long count = refundMapper.selectCount(new LambdaQueryWrapper<GiftCardRefund>()
                .eq(GiftCardRefund::getOrderNo, orderNo)
                .eq(GiftCardRefund::getStatus, REFUND_REFUNDING));
        return count != null && count > 0;
    }

    private GiftCardOrder requireOwnedOrder(Long userId, String orderNo) {
        if (!StringUtils.hasText(orderNo)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单号不能为空");
        }
        GiftCardOrder order = orderMapper.selectOne(new LambdaQueryWrapper<GiftCardOrder>()
                .eq(GiftCardOrder::getOrderNo, orderNo)
                .eq(GiftCardOrder::getUserId, userId));
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "礼品卡订单不存在");
        }
        return order;
    }

    private GiftCardOrder enrich(GiftCardOrder order) {
        Map<String, Object> row = loadDenominationMetadata(List.of(order.getDenominationId()))
                .get(order.getDenominationId());
        if (row != null) {
            order.setCardName(stringValue(row.get("card_name")));
            order.setCardImage(stringValue(row.get("card_image")));
            order.setGroupTitle(stringValue(row.get("group_title")));
            order.setSalePrice(longValue(row.get("sale_price")));
        }
        applyRefundSummary(order);
        return order;
    }

    private void applyRefundSummary(GiftCardOrder order) {
        if (order == null || !StringUtils.hasText(order.getOrderNo())) {
            return;
        }
        GiftCardRefund latest = refundMapper.selectOne(new LambdaQueryWrapper<GiftCardRefund>()
                .eq(GiftCardRefund::getOrderNo, order.getOrderNo())
                .orderByDesc(GiftCardRefund::getId)
                .last("limit 1"));
        if (latest != null) {
            order.setRefundStatus(latest.getStatus());
            order.setRefundFailReason(latest.getFailReason());
        }
    }

    private void applyDenominationMetadata(GiftCard card, Map<String, Object> row) {
        if (row == null) {
            return;
        }
        card.setCardName(stringValue(row.get("card_name")));
        card.setCardImage(stringValue(row.get("card_image")));
        card.setGroupTitle(stringValue(row.get("group_title")));
        card.setAmount(longValue(row.get("amount")));
        card.setSalePrice(longValue(row.get("sale_price")));
    }

    private List<Long> denominationIdsFromCards(List<GiftCard> cards) {
        Set<Long> ids = new HashSet<>();
        for (GiftCard card : cards) {
            if (card.getDenominationId() != null) {
                ids.add(card.getDenominationId());
            }
        }
        return new ArrayList<>(ids);
    }

    private List<Long> denominationIdsFromOrders(List<GiftCardOrder> orders) {
        Set<Long> ids = new HashSet<>();
        for (GiftCardOrder order : orders) {
            if (order.getDenominationId() != null) {
                ids.add(order.getDenominationId());
            }
        }
        return new ArrayList<>(ids);
    }

    /** 历史展示元数据必须直查，不能经过 MyBatis 逻辑删除过滤。 */
    private Map<Long, Map<String, Object>> loadDenominationMetadata(List<Long> denominationIds) {
        if (denominationIds == null || denominationIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(denominationIds.size(), "?"));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id as denomination_id, coalesce(card_name, name) as card_name, card_image, "
                        + "group_title, amount, sale_price from gift_card_denomination "
                        + "where id in (" + placeholders + ")",
                denominationIds.toArray());
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Long id = longValue(row.get("denomination_id"));
            if (id != null) {
                result.put(id, row);
            }
        }
        return result;
    }

    private boolean isDeleted(Integer deleted) {
        return deleted != null && deleted != 0;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String nextNo(String prefix) {
        return prefix + LocalDateTime.now().format(FMT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }
}
