package com.wuling.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.common.mq.MqConstants;
import com.wuling.common.mq.event.CouponEvent;
import com.wuling.common.mq.event.OrderRefundedEvent;
import com.wuling.common.outbox.OutboxService;
import com.wuling.trade.dto.OrderDTO;
import com.wuling.trade.entity.MallAfterSale;
import com.wuling.trade.entity.Order;
import com.wuling.trade.entity.OrderItem;
import com.wuling.trade.entity.Payment;
import com.wuling.trade.entity.Refund;
import com.wuling.trade.mapper.OrderItemMapper;
import com.wuling.trade.mapper.OrderMapper;
import com.wuling.trade.mapper.MallAfterSaleMapper;
import com.wuling.trade.mapper.PaymentMapper;
import com.wuling.trade.mapper.RefundMapper;
import com.wuling.trade.pay.storedvalue.StoredValueBalancePort;
import com.wuling.trade.port.SettlementQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 整单退款（对齐方案 D-07 + 退款三态改造）。
 *
 * <p><b>退款状态机（2026-09 改造）</b>：
 * <ul>
 *   <li>{@code REFUNDING} —— 退款中：已受理、钱尚未到账（默认态）；</li>
 *   <li>{@code FAILED} —— 退款失败：同步下单失败或微信回调 ABNORMAL/CLOSED；</li>
 *   <li>{@code SUCCESS} —— 退款成功：微信退款结果通知 SUCCESS。</li>
 * </ul>
 *
 * <p><b>关键资金安全约束</b>：
 * <ol>
 *   <li>退款是<b>异步</b>的：微信接口返回受理成功只代表「已受理」，不代表钱已到账，
 *       最终结果以退款结果通知（{@code WxRefundNotifyController}）为准；</li>
 *   <li>订单主状态受理后保持 <b>PAID</b>：仅置
 *       {@code refund_status=PENDING}；退款成功才置 {@code CANCELED + refund_status=REFUNDED}，失败仍为 PAID；</li>
 *   <li>台账冲正（{@link OrderRefundedEvent}）<b>只在退款成功后发布</b>，
 *       否则「钱还没退、账先冲了」会造成资金对不上；</li>
 *   <li>退款成功后由 finance 按原结算记录逐账户扣回，余额不足时整批回滚并记录对账异常。</li>
 * </ol>
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 退款单状态：退款中 */
    public static final String STATUS_REFUNDING = "REFUNDING";
    /** 退款单状态：退款失败 */
    public static final String STATUS_FAILED = "FAILED";
    /** 退款单状态：退款成功 */
    public static final String STATUS_SUCCESS = "SUCCESS";

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final RefundMapper refundMapper;
    private final PaymentMapper paymentMapper;
    private final SettlementQueryPort settlementQueryPort;
    private final OrderService orderService;
    private final PaymentGatewayResolver gatewayResolver;
    private final OutboxService outboxService;
    private final StoredValueBalancePort storedValueBalancePort;
    private final MallStockService mallStockService;
    private final MallAfterSaleMapper afterSaleMapper;

    /** 延迟重查必须重新进入 Spring 事务代理。 */
    @Autowired
    private ObjectProvider<RefundService> self;

    /**
     * 是否启用「退款受理后即时重查」。
     *
     * <p>默认开启：微信退款回调（refund-notify）可能因域名不可达 / 验签失败而丢失，
     * 只靠定时兜底会让用户盯着「退款中」等待；受理后短延时主动查询一次可大幅压缩延迟。
     * 置 false 可关闭（仅用于排查「即时重查是否与回调相互干扰」）。
     */
    @Value("${app.refund.quick-recheck-enabled:true}")
    private boolean quickRecheckEnabled;

    /**
     * 即时重查的延迟（毫秒）。
     *
     * <p>退避太短会在微信尚未完成退款时白查一次（仍会由定时兜底接管），
     * 太长则延迟优势不明显；30 秒是「微信小额退款通常已到账」与「用户可接受等待」的折中。
     */
    @Value("${app.refund.quick-recheck-delay-ms:30000}")
    private long quickRecheckDelayMs;

    /**
     * 归集明细查询客户端（server 内部接口）。
     *
     * <p>用字段注入而非构造参数：RefundService 有两套构造重载，
     * 且单元测试大量使用 9 参版本，加参数会牵连 20+ 处测试。
     * {@code required=false} 保证测试环境（无该 Bean）仍可构造。
     */
    @Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("serverInternalRestClient")
    private org.springframework.web.client.RestClient serverInternalRestClient;

    public RefundService(OrderMapper orderMapper,
                         OrderItemMapper orderItemMapper,
                         RefundMapper refundMapper,
                         PaymentMapper paymentMapper,
                         SettlementQueryPort settlementQueryPort,
                         OrderService orderService,
                         PaymentGatewayResolver gatewayResolver,
                         OutboxService outboxService,
                         StoredValueBalancePort storedValueBalancePort) {
        this(orderMapper, orderItemMapper, refundMapper, paymentMapper, settlementQueryPort,
                orderService, gatewayResolver, outboxService, storedValueBalancePort, null, null);
    }

    @Autowired
    public RefundService(OrderMapper orderMapper,
                         OrderItemMapper orderItemMapper,
                         RefundMapper refundMapper,
                         PaymentMapper paymentMapper,
                         SettlementQueryPort settlementQueryPort,
                         OrderService orderService,
                         PaymentGatewayResolver gatewayResolver,
                         OutboxService outboxService,
                         StoredValueBalancePort storedValueBalancePort,
                         MallStockService mallStockService,
                         MallAfterSaleMapper afterSaleMapper) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.refundMapper = refundMapper;
        this.paymentMapper = paymentMapper;
        this.settlementQueryPort = settlementQueryPort;
        this.orderService = orderService;
        this.gatewayResolver = gatewayResolver;
        this.outboxService = outboxService;
        this.storedValueBalancePort = storedValueBalancePort;
        this.mallStockService = mallStockService;
        this.afterSaleMapper = afterSaleMapper;
    }

    /**
     * 售后审核通过后的部分/整笔退款入口。
     *
     * <p>与整单 refund() 分开：售后单已由服务端计算金额，退款单必须携带
     * afterSaleId 与订单实付快照，成功时才能只回补本次数量并计算真实退款比例。
     */
    @Transactional(rollbackFor = Exception.class)
    public Refund refundAfterSale(Order order, MallAfterSale afterSale, String reason) {
        if (order == null || afterSale == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "售后退款参数不完整");
        }
        order = orderService.lockForUpdate(order.getOrderNo());
        if (!"MALL".equalsIgnoreCase(order.getOrderType())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅商城订单支持售后退款");
        }
        if ("PENDING".equalsIgnoreCase(order.getRefundStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单退款处理中，请勿重复申请");
        }
        LocalDateTime now = LocalDateTime.now();
        Refund refund = new Refund();
        refund.setRefundNo(nextNo());
        refund.setOrderId(order.getId());
        refund.setOrderNo(order.getOrderNo());
        refund.setAfterSaleId(afterSale.getId());
        refund.setAmount(afterSale.getRefundAmount());
        refund.setOriginalAmount(order.getPaidAmount());
        refund.setStatus(STATUS_REFUNDING);
        refund.setReason(reason);
        refund.setApplyTime(now);
        SettlementQueryPort.SettlementStatus settlement = settlementQueryPort.query(order.getId());
        if (settlement != null && settlement.isLegacyInvestorShare()
                && refund.getAmount() < refund.getOriginalAmount()) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "历史商城订单含投资人分账，部分退款需先完成账务核对");
        }
        if (settlement != null && settlement.isHasSettlement()
                && refund.getAmount() < refund.getOriginalAmount()) {
            fillMallRefundShares(new OrderRefundedEvent(), order, afterSale, refund);
        }
        refundMapper.insert(refund);
        orderService.markRefundPending(order);
        applyToGateway(refund);
        scheduleQuickRecheck(refund);
        return refund;
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderDTO refund(String orderNo, String reason) {
        Order order = orderService.lockForUpdate(orderNo);
        validateRefundable(order);

        LocalDateTime now = LocalDateTime.now();
        Refund refund = new Refund();
        refund.setRefundNo(nextNo());
        refund.setOrderId(order.getId());
        refund.setOrderNo(orderNo);
        refund.setAmount(order.getPaidAmount());
        refund.setOriginalAmount(order.getPaidAmount());
        refund.setStatus(STATUS_REFUNDING);
        refund.setReason(reason);
        refund.setApplyTime(now);
        refundMapper.insert(refund);

        // 订单先标记「退款中」，真正的 REFUNDED 等退款成功回调再置
        orderService.markRefundPending(order);

        // 调用三方退款下单（放本方法尾部：事务仍持有行锁，但下单失败走 FAILED 不抛异常，
        // 避免事务回滚把 REFUNDING 记录也回滚掉 —— 失败也应有记录可查/可重试）
        applyToGateway(refund);

        // 若受理后仍是 REFUNDING，安排一次延迟主动查询：
        // 微信退款结果通知（refund-notify）可能因域名不可达 / 验签失败而丢失，
        // 届时本地只能等定时兜底（默认 1 分钟）。这里在受理后短延时内主动查一次，
        // 通常 20~40 秒即可把状态推进为 SUCCESS，用户无需盯着「退款中」等待。
        scheduleQuickRecheck(refund);

        return orderService.toDTO(order, loadItems(order.getId()));
    }

    /**
     * 调用支付通道发起退款；同步失败置 FAILED（订单保持退款前状态，可重试）。
     */
    private void applyToGateway(Refund refund) {
        Payment payment = paidPayment(refund.getOrderNo());
        long originalTotal = payment != null && payment.getAmount() != null ? payment.getAmount()
                : refund.getOriginalAmount() != null ? refund.getOriginalAmount() : refund.getAmount();
        if (!java.util.Objects.equals(refund.getOriginalAmount(), originalTotal)) {
            refund.setOriginalAmount(originalTotal);
            Refund amountSnapshot = new Refund();
            amountSnapshot.setId(refund.getId());
            amountSnapshot.setOriginalAmount(originalTotal);
            refundMapper.updateById(amountSnapshot);
        }
        if (payment != null && PaymentService.isStoredValueChannel(payment.getChannel())) {
            Order order = orderMapper.selectById(refund.getOrderId());
            if (order == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "原订单不存在");
            }
            // 整单保持原业务号；多次部分售后按售后单区分，重试仍复用同一个业务号。
            String businessNo = "REFUND:" + refund.getOrderNo()
                    + (refund.getAfterSaleId() == null ? "" : ":AS:" + refund.getAfterSaleId());
            storedValueBalancePort.refund(order.getUserId(), refund.getAmount(),
                    businessNo, order.getStoreSubjectId());
            markSuccess(refund);
            return;
        }

        PaymentGateway gateway = gatewayResolver.forPayment(payment);
        PaymentGateway.RefundApplyResult result;
        // 归集明细只对易宝有意义：微信没有分账概念，传了反而多余。
        // 分流调用也保持既有测试与微信链路的调用形态不变。
        if ("YEEPAY".equalsIgnoreCase(gateway.channel())) {
            java.util.List<java.util.Map<String, Object>> divideBackDetail =
                    loadDivideBackDetail(refund.getOrderNo(), refund);
            result = gateway.refund(refund.getOrderNo(), refund.getRefundNo(), refund.getAmount(),
                    originalTotal, refund.getReason(), divideBackDetail);
        } else {
            result = gateway.refund(refund.getOrderNo(), refund.getRefundNo(), refund.getAmount(),
                    originalTotal, refund.getReason());
        }

        if (result == null) {
            // mock 通道不支持退款：保持 REFUNDING（由外部/定时补偿推进），不伪造成功
            return;
        }
        if (result.unknown()) {
            // 请求可能已受理；保留原退款号查单，不能进入可换号重试的FAILED。
            log.warn("退款请求结果待确认，保留退款中 refundNo={}", refund.getRefundNo());
            return;
        }
        if (result.accepted()) {
            if (result.thirdRefundNo() != null) {
                Refund patch = new Refund();
                patch.setId(refund.getId());
                patch.setThirdRefundNo(result.thirdRefundNo());
                refundMapper.updateById(patch);
                refund.setThirdRefundNo(result.thirdRefundNo());
            }
            return;
        }
        markFailed(refund, result.failReason());
    }

    /**
     * 退款结果通知处理：SUCCESS 置成功并完成订单退款与台账冲正；ABNORMAL/CLOSED 置失败。
     *
     * @return true 本次真正推进了状态（首次）；false 已处理过（幂等跳过）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean onRefundResult(String refundNo, boolean success, String failReason) {
        Refund refund = lockRefund(refundNo);
        // 幂等：终态不重复处理
        if (STATUS_SUCCESS.equals(refund.getStatus()) || STATUS_FAILED.equals(refund.getStatus())) {
            return false;
        }
        if (success) {
            return markSuccess(refund);
        }
        return markFailed(refund, failReason);
    }

    /**
     * 主动查询三方退款状态并推进本地状态（退款兜底扫描专用）。
     *
     * <p>REFUNDING 单查三方真实状态，SUCCESS 推进成功，
     * CLOSED/ABNORMAL 置失败，PROCESSING/查询失败保持 REFUNDING 下轮重试。
     * 已 SUCCESS 的售后退款仅查证本地成功快照、修复全退订单终态，不重放外部副作用。
     *
     * @return true 本次推进了退款或订单状态；false 未推进
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean syncRefundStatus(String orderNo, String refundNo) {
        Refund refund = lockRefund(refundNo);
        if (!orderNo.equals(refund.getOrderNo())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "退款单与原订单不匹配");
        }
        if (STATUS_SUCCESS.equals(refund.getStatus())) return repairAfterSaleOrderProjection(refund);
        if (!STATUS_REFUNDING.equals(refund.getStatus())) return false;
        Payment payment = paidPayment(orderNo);
        if (payment != null && PaymentService.isStoredValueChannel(payment.getChannel())) return false;
        String status = gatewayResolver.forPayment(payment).queryRefund(orderNo, refundNo);
        if (status == null) {
            return false;
        }
        if ("SUCCESS".equals(status)) {
            return markSuccess(refund);
        }
        if ("CLOSED".equals(status) || "ABNORMAL".equals(status)) {
            return markFailed(refund, "微信退款状态：" + status);
        }
        return false;
    }
    /**
     * 按退款单号主动查询三方退款状态并推进本地状态（后台运维手动触发）。
     *
     * <p>先按退款单号定位退款单，再调 {@link #syncRefundStatus} 查询并推进。
     * 返回查询后的最新状态与是否真正推进，便于运维确认结果。
     */
    @Transactional(rollbackFor = Exception.class)
    public RefundSyncResult syncRefundStatusByRefundNo(String refundNo) {
        Refund refund = refundMapper.selectOne(new LambdaQueryWrapper<Refund>()
                .eq(Refund::getRefundNo, refundNo));
        if (refund == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "退款单不存在：" + refundNo);
        }
        boolean advanced = syncRefundStatus(refund.getOrderNo(), refund.getRefundNo());
        Refund latest = refundMapper.selectById(refund.getId());
        return new RefundSyncResult(
                refund.getRefundNo(),
                refund.getOrderNo(),
                latest == null ? refund.getStatus() : latest.getStatus(),
                advanced);
    }


    /**
     * 重新退款：仅退款失败(FAILED)可重试，走同一套下单逻辑。
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public OrderDTO retry(Long refundId) {
        Refund snapshot = refundMapper.selectById(refundId);
        if (snapshot == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "退款单不存在");
        }
        // 和售后申请、审核、通知保持同一顺序：订单 → 退款 → 售后。
        Order order = orderService.lockForUpdate(snapshot.getOrderNo());
        Refund refund = refundMapper.selectOne(new LambdaQueryWrapper<Refund>()
                .eq(Refund::getId, refundId).last("for update"));
        if (refund == null) throw new BusinessException(ResultCode.NOT_FOUND, "退款单不存在");
        if (!STATUS_FAILED.equals(refund.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅退款失败的单据可重新退款");
        }
        List<Refund> occupiedRefunds = refund.getAfterSaleId() == null ? List.of()
                : refundMapper.selectList(new LambdaQueryWrapper<Refund>()
                        .eq(Refund::getOrderId, order.getId())
                        .in(Refund::getStatus, STATUS_REFUNDING, STATUS_SUCCESS).last("for update"));
        MallAfterSale afterSale = refund.getAfterSaleId() == null ? null
                : afterSaleMapper.selectOne(new LambdaQueryWrapper<MallAfterSale>()
                        .eq(MallAfterSale::getId, refund.getAfterSaleId()).last("for update"));
        if (refund.getAfterSaleId() != null) {
            if (afterSale == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "关联售后单不存在");
            }
            if (!"FAILED".equals(afterSale.getStatus())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "仅退款失败的售后单可重试");
            }
            validateAfterSaleRetry(order, refund, afterSale, occupiedRefunds);
            SettlementQueryPort.SettlementStatus settlement = settlementQueryPort.query(order.getId());
            if (settlement != null && settlement.isLegacyInvestorShare()
                    && refund.getOriginalAmount() != null && refund.getAmount() < refund.getOriginalAmount()) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "历史商城订单含投资人分账，部分退款需先完成账务核对");
            }
        } else {
            validateRefundable(order);
        }

        // 只有原FAILED记录的抢占者可以生成新通道退款；并发请求失败即回滚。
        String nextRefundNo = nextNo();
        LocalDateTime applyTime = LocalDateTime.now();
        int claimed = refundMapper.update(null, new LambdaUpdateWrapper<Refund>()
                .eq(Refund::getId, refund.getId())
                .eq(Refund::getRefundNo, refund.getRefundNo())
                .eq(Refund::getStatus, STATUS_FAILED)
                .set(Refund::getRefundNo, nextRefundNo)
                .set(Refund::getStatus, STATUS_REFUNDING)
                .set(Refund::getApplyTime, applyTime)
                .set(Refund::getThirdRefundNo, null));
        if (claimed != 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "退款已在处理中，请勿重复重试");
        }
        // 售后重试复用原退款记录，保留afterSaleId与originalAmount。
        refund.setRefundNo(nextRefundNo);
        refund.setStatus(STATUS_REFUNDING);
        refund.setApplyTime(applyTime);
        refund.setThirdRefundNo(null);

        if (afterSale != null) {
            MallAfterSale patch = new MallAfterSale();
            patch.setId(afterSale.getId());
            patch.setStatus("REFUNDING");
            afterSaleMapper.updateById(patch);
            afterSale.setStatus("REFUNDING");
        }
        orderService.markRefundPending(order);

        applyToGateway(refund);
        scheduleQuickRecheck(refund);
        return orderService.toDTO(order, loadItems(order.getId()));
    }

    /** 当前读保证参与外层REPEATABLE_READ事务时也不会沿用等锁前的额度快照。 */
    private void validateAfterSaleRetry(Order order, Refund refund, MallAfterSale afterSale,
                                       List<Refund> occupiedRefunds) {
        if (afterSale.getReturnReceivedTime() == null
                || !org.springframework.util.StringUtils.hasText(afterSale.getReturnTrackingNo())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请先确认收到退货，再重试退款");
        }
        if (!"MALL".equalsIgnoreCase(order.getOrderType())
                || !List.of(OrderService.STATUS_SHIPPED, OrderService.STATUS_COMPLETED).contains(order.getStatus())
                || "PENDING".equalsIgnoreCase(order.getRefundStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单当前状态不支持重试售后退款");
        }
        if (!order.getId().equals(afterSale.getOrderId())
                || !java.util.Objects.equals(refund.getAmount(), afterSale.getRefundAmount())
                || refund.getAmount() == null || refund.getAmount() <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "售后退款与原订单不一致");
        }
        List<MallAfterSale> occupied = afterSaleMapper.selectList(new LambdaQueryWrapper<MallAfterSale>()
                .eq(MallAfterSale::getOrderId, order.getId())
                .in(MallAfterSale::getStatus, "PENDING", "RETURN_SHIPPED", "RETURN_RECEIVED", "APPROVED", "REFUNDING", "SUCCESS")
                .last("for update"));
        Map<Long, Integer> used = new LinkedHashMap<>();
        for (MallAfterSale other : occupied) {
            if (afterSale.getId().equals(other.getId())) continue;
            if (List.of("PENDING", "RETURN_SHIPPED", "RETURN_RECEIVED", "APPROVED", "REFUNDING").contains(other.getStatus())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "该订单已有其他处理中的售后申请");
            }
            quantities(other).forEach((id, quantity) -> used.merge(id, quantity, Math::addExact));
        }
        Map<Long, OrderItem> originals = new LinkedHashMap<>();
        for (OrderItem item : loadItems(order.getId())) originals.put(item.getId(), item);
        for (Map.Entry<Long, Integer> requested : quantities(afterSale).entrySet()) {
            OrderItem item = originals.get(requested.getKey());
            int total = item == null || item.getQuantity() == null ? 0 : item.getQuantity();
            if (item == null || !order.getId().equals(item.getOrderId())
                    || requested.getValue() > Math.max(0, total - used.getOrDefault(item.getId(), 0))) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "原售后数量已被其他售后占用，不能重试");
            }
        }
        Payment payment = paidPayment(order.getOrderNo());
        long originalTotal = payment != null && payment.getAmount() != null ? payment.getAmount()
                : refund.getOriginalAmount() != null ? refund.getOriginalAmount() : order.getPaidAmount();
        long occupiedAmount = 0L;
        for (Refund other : occupiedRefunds) {
            if (!refund.getId().equals(other.getId()) && other.getAmount() != null) {
                occupiedAmount = Math.addExact(occupiedAmount, other.getAmount());
            }
        }
        if (originalTotal <= 0 || refund.getAmount() > originalTotal - occupiedAmount) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单剩余可退金额不足，不能重试原售后");
        }
    }

    private Map<Long, Integer> quantities(MallAfterSale afterSale) {
        try {
            var items = JSON.readTree(afterSale.getItems() == null ? "[]" : afterSale.getItems());
            if (!items.isArray() || items.isEmpty()) throw new IllegalArgumentException();
            Map<Long, Integer> quantities = new LinkedHashMap<>();
            for (var item : items) {
                long id = item.path("orderItemId").asLong(0);
                int quantity = item.path("quantity").asInt(0);
                if (id <= 0 || quantity <= 0) throw new IllegalArgumentException();
                quantities.merge(id, quantity, Math::addExact);
            }
            return quantities;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "售后商品快照损坏，不能重试退款");
        }
    }

    private Refund lockRefund(String refundNo) {
        Refund snapshot = refundMapper.selectOne(new LambdaQueryWrapper<Refund>()
                .eq(Refund::getRefundNo, refundNo));
        if (snapshot == null) throw new BusinessException(ResultCode.NOT_FOUND, "退款单不存在：" + refundNo);
        orderService.lockForUpdate(snapshot.getOrderNo());
        Refund current = refundMapper.selectOne(new LambdaQueryWrapper<Refund>()
                .eq(Refund::getRefundNo, refundNo).last("for update"));
        if (current == null) throw new BusinessException(ResultCode.NOT_FOUND, "退款单不存在：" + refundNo);
        return current;
    }

    /**
     * 受理退款后安排一次「延迟主动查询」。
     *
     * <p><b>为什么需要它</b>：退款到账由微信异步完成，本地状态依赖回调推进；
     * 回调未送达时只能等定时兜底（间隔 1 分钟），用户会看到「退款中」滞留。
     * 本方法在受理后的短延时窗口内主动查询一次，把常见情况（微信已退款成功）
     * 的感知延迟从「分钟级」压到「几十秒级」。
     *
     * <p><b>安全边界</b>：
     * <ul>
     *   <li>查询失败 / 仍未终态时静默忽略，状态保持不变，由定时兜底继续接管；</li>
     *   <li>与定时兜底共用 {@link #syncRefundStatus}，其内部 {@link #onRefundResult}
     *       对终态退款单幂等跳过，因此重复查询不会重复冲正台账；</li>
     *   <li>查询在下单事务提交后异步执行，不占用/不延长原事务与行锁；</li>
     *   <li>mock 通道 {@code queryRefund} 返回 null，本方法自然跳过。</li>
     * </ul>
     */
    private void scheduleQuickRecheck(Refund refund) {
        if (!quickRecheckEnabled) {
            return;
        }
        if (!STATUS_REFUNDING.equals(refund.getStatus())) {
            // 储值同步退款等场景已直接成功，无需再查
            return;
        }
        Runnable schedule = () -> java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(Math.max(0, quickRecheckDelayMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                self.getObject().syncRefundStatus(refund.getOrderNo(), refund.getRefundNo());
            } catch (Exception e) {
                // 主动查询失败不是异常路径：定时兜底会继续重试
                log.warn("退款受理后即时重查失败 refundNo={} err={}", refund.getRefundNo(), e.getMessage());
            }
        });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { schedule.run(); }
            });
        }
    }

    private boolean markSuccess(Refund refund) {
        if (!advance(refund, STATUS_SUCCESS, null)) return false;
        Order order = orderService.lockForUpdate(refund.getOrderNo());
        MallAfterSale afterSale = refund.getAfterSaleId() == null
                ? null : afterSaleMapper.selectById(refund.getAfterSaleId());
        boolean fullRefund = true;
        Map<Long, Integer> restoredMap = Map.of();
        if (afterSale != null) {
            // 同步储值退款进入这里时售后仍为 PENDING；先在本地事务内置成功，
            // 再查累计数量，才能把此前成功售后与本次合并。后续失败会整体回滚。
            MallAfterSale afterSalePatch = new MallAfterSale();
            afterSalePatch.setId(afterSale.getId());
            afterSalePatch.setStatus("SUCCESS");
            afterSalePatch.setCompleteTime(LocalDateTime.now());
            afterSaleMapper.updateById(afterSalePatch);
            afterSale.setStatus("SUCCESS");
            afterSale.setCompleteTime(afterSalePatch.getCompleteTime());
            restoredMap = mallStockService == null
                    ? Map.of() : mallStockService.restoreForAfterSale(order, afterSale);
            List<OrderItem> orderItems = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                    .eq(OrderItem::getOrderId, order.getId()));
            Map<Long, Integer> restored = restoredMap;
            fullRefund = orderItems.stream().allMatch(item -> restored.getOrDefault(item.getId(), 0)
                    >= (item.getQuantity() == null ? 0 : item.getQuantity()));
        } else if (order != null && "MALL".equalsIgnoreCase(order.getOrderType())
                && mallStockService != null) {
            mallStockService.restoreForRefund(order);
        }

        if (order != null) {
            if (afterSale != null) {
                List<OrderItem> orderItems = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getOrderId, order.getId()));
                int total = orderItems.stream().mapToInt(item -> item.getQuantity() == null ? 0 : item.getQuantity()).sum();
                Map<Long, Integer> restored = restoredMap;
                int refunded = orderItems.stream().mapToInt(item -> restored.getOrDefault(item.getId(), 0)).sum();
                orderService.markAfterSaleRefunded(order, fullRefund ? total : refunded, total);
            } else {
                orderService.markRefunded(order);
            }
            SettlementQueryPort.SettlementStatus settlementStatus = settlementQueryPort.query(order.getId());
            if (settlementStatus.isHasSettlement()) {
                OrderRefundedEvent event = new OrderRefundedEvent();
                event.setOrderId(order.getId());
                event.setOrderNo(order.getOrderNo());
                event.setRefundAmount(refund.getAmount());
                event.setRefundNo(refund.getRefundNo());
                event.setRefundRatio(refundRatio(refund));
                if (afterSale != null && refund.getAmount() != null && refund.getOriginalAmount() != null
                        && refund.getAmount() < refund.getOriginalAmount()
                        && !settlementStatus.isLegacyInvestorShare()) {
                    fillMallRefundShares(event, order, afterSale, refund);
                }
                outboxService.enqueue(
                        "ORDER", order.getOrderNo(), "ORDER_REFUNDED",
                        MqConstants.FINANCE_REVERSE_ROUTING_KEY, order.getOrderNo(), event);
            }
            if (order.getCouponId() != null && fullRefund) {
                CouponEvent event = new CouponEvent();
                event.setAction(CouponEvent.ACTION_RESTORE_REFUND);
                event.setUserId(order.getUserId());
                event.setUserCouponId(order.getCouponId());
                event.setOrderNo(order.getOrderNo());
                outboxService.enqueue(
                        "ORDER", order.getOrderNo(), "COUPON_RESTORE_REFUND",
                        MqConstants.COUPON_EVENT_ROUTING_KEY, order.getOrderNo(), event);
            }
        }
        return true;
    }

    /** 原成功退款查证：仅修复历史售后累计遗漏的订单终态，不重放任何外部副作用。 */
    private boolean repairAfterSaleOrderProjection(Refund refund) {
        if (refund.getAfterSaleId() == null || afterSaleMapper == null) return false;
        Order order = orderService.lockForUpdate(refund.getOrderNo());
        if (!java.util.Objects.equals(order.getId(), refund.getOrderId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "退款单与原订单不匹配");
        }
        if (!"MALL".equalsIgnoreCase(order.getOrderType())) return false;
        MallAfterSale requested = afterSaleMapper.selectById(refund.getAfterSaleId());
        validateProjectionAfterSale(order, refund, requested);
        if (!STATUS_SUCCESS.equals(requested.getStatus())
                || (!OrderService.STATUS_SHIPPED.equals(order.getStatus())
                && !OrderService.STATUS_COMPLETED.equals(order.getStatus()))) return false;

        Payment payment = paidPayment(order.getOrderNo());
        if (payment == null || payment.getAmount() == null || payment.getAmount() <= 0
                || (payment.getOrderId() != null && !order.getId().equals(payment.getOrderId()))) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "原支付记录不可查证");
        }
        long originalAmount = payment.getAmount();
        List<OrderItem> orderItems = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, order.getId()));
        Map<Long, Integer> originalQuantities = new LinkedHashMap<>();
        for (OrderItem item : orderItems) {
            originalQuantities.put(item.getId(), item.getQuantity() == null ? 0 : item.getQuantity());
        }
        Map<Long, Integer> refundedQuantities = new LinkedHashMap<>();
        java.util.Set<Long> countedAfterSales = new java.util.HashSet<>();
        long refundedAmount = 0;
        for (Refund completed : refundMapper.selectList(new LambdaQueryWrapper<Refund>()
                .eq(Refund::getOrderId, order.getId()).eq(Refund::getStatus, STATUS_SUCCESS))) {
            if (!java.util.Objects.equals(order.getId(), completed.getOrderId())
                    || !order.getOrderNo().equals(completed.getOrderNo())
                    || completed.getAmount() == null || completed.getAmount() <= 0
                    || completed.getAmount() > originalAmount - refundedAmount) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "成功退款金额与原订单不一致");
            }
            refundedAmount += completed.getAmount();
            if (completed.getAfterSaleId() == null) continue;
            MallAfterSale afterSale = afterSaleMapper.selectById(completed.getAfterSaleId());
            validateProjectionAfterSale(order, completed, afterSale);
            if (!STATUS_SUCCESS.equals(afterSale.getStatus())) continue;
            if (!countedAfterSales.add(afterSale.getId())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "同一售后存在重复成功退款");
            }
            for (Map.Entry<Long, Integer> entry : quantities(afterSale).entrySet()) {
                int previous = refundedQuantities.getOrDefault(entry.getKey(), 0);
                if (entry.getValue() > originalQuantities.getOrDefault(entry.getKey(), 0) - previous) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "成功售后数量超过原订单");
                }
                refundedQuantities.put(entry.getKey(), previous + entry.getValue());
            }
        }
        boolean full = !orderItems.isEmpty() && orderItems.stream().allMatch(item ->
                refundedQuantities.getOrDefault(item.getId(), 0).equals(originalQuantities.get(item.getId())));
        if (!full || refundedAmount != originalAmount) return false;
        int total = orderItems.stream().mapToInt(item -> originalQuantities.get(item.getId())).sum();
        return orderService.markAfterSaleRefunded(order, total, total);
    }

    private void validateProjectionAfterSale(Order order, Refund refund, MallAfterSale afterSale) {
        if (afterSale == null || !java.util.Objects.equals(order.getId(), afterSale.getOrderId())
                || !order.getOrderNo().equals(afterSale.getOrderNo())
                || !java.util.Objects.equals(afterSale.getRefundId(), refund.getId())
                || !java.util.Objects.equals(afterSale.getRefundAmount(), refund.getAmount())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "售后退款快照与原订单不匹配");
        }
    }

    private void fillMallRefundShares(OrderRefundedEvent event, Order order,
                                      MallAfterSale afterSale, Refund refund) {
        Map<Long, Integer> returned = quantities(afterSale);
        Map<Long, Long> subjects = new LinkedHashMap<>();
        long supplierOriginal = 0L;
        long leaderOriginal = 0L;
        long commissionOriginal = 0L;
        long supplierRefund = 0L;
        long leaderRefund = 0L;
        long commissionRefund = 0L;
        long refundCheck = 0L;
        for (OrderItem item : loadItems(order.getId())) {
            int originalQty = item.getQuantity() == null ? 0 : item.getQuantity();
            int refundQty = returned.containsKey(item.getId()) ? returned.remove(item.getId()) : 0;
            if (refundQty < 0 || refundQty > originalQty) {
                throw new IllegalStateException("退货数量与订单快照不一致 itemId=" + item.getId());
            }
            long cost = item.getCostPrice() == null ? 0L : item.getCostPrice();
            long leader = order.getLeaderSubjectId() == null || item.getDistributionCommission() == null
                    ? 0L : item.getDistributionCommission();
            long commission = item.getPlatformCommission() == null ? 0L : item.getPlatformCommission();
            supplierOriginal += cost * originalQty;
            leaderOriginal += leader * originalQty;
            commissionOriginal += commission * originalQty;
            if (refundQty == 0) continue;
            if (item.getSupplierSubjectId() == null) {
                throw new IllegalStateException("退货商品缺少供应商主体 itemId=" + item.getId());
            }
            subjects.merge(item.getSupplierSubjectId(), cost * refundQty, Long::sum);
            supplierRefund += cost * refundQty;
            leaderRefund += leader * refundQty;
            commissionRefund += commission * refundQty;
            refundCheck += (item.getUnitPrice() == null ? 0L : item.getUnitPrice()) * refundQty;
        }
        if (!returned.isEmpty() || refundCheck != refund.getAmount()) {
            throw new IllegalStateException("退货明细与退款金额不一致 refundNo=" + refund.getRefundNo());
        }
        if (leaderRefund != 0L) {
            if (order.getLeaderSubjectId() == null) {
                throw new IllegalStateException("退货商品缺少团长主体 refundNo=" + refund.getRefundNo());
            }
            subjects.merge(order.getLeaderSubjectId(), leaderRefund, Long::sum);
        }
        long platformOriginal = order.getPaidAmount() - supplierOriginal - leaderOriginal;
        long platformRefund = refund.getAmount() - supplierRefund - leaderRefund;
        if (platformOriginal < 0L) commissionRefund = 0L;
        long bookedCommissionOriginal = platformOriginal < 0L ? 0L : commissionOriginal;
        event.setSubjectRefundAmounts(subjects);
        event.setPlatformCommissionRefund(commissionRefund);
        event.setPlatformBonusRefund(platformRefund - commissionRefund);
        event.setPlatformCommissionOriginal(bookedCommissionOriginal);
        event.setPlatformBonusOriginal(platformOriginal - bookedCommissionOriginal);
    }

    private int refundRatio(Refund refund) {
        long original = refund.getOriginalAmount() == null ? 0L : refund.getOriginalAmount();
        if (original <= 0L) return 10000;
        long ratio = Math.round(refund.getAmount() * 10000.0 / original);
        return (int) Math.max(1L, Math.min(10000L, ratio));
    }

    void markSuccessForTest(Refund refund) {
        markSuccess(refund);
    }

    private boolean markFailed(Refund refund, String failReason) {
        if (!advance(refund, STATUS_FAILED, failReason)) return false;
        Order order = orderService.lockForUpdate(refund.getOrderNo());

        if (refund.getAfterSaleId() != null) {
            MallAfterSale afterSale = afterSaleMapper.selectById(refund.getAfterSaleId());
            if (afterSale != null) {
                MallAfterSale afterSalePatch = new MallAfterSale();
                afterSalePatch.setId(afterSale.getId());
                afterSalePatch.setStatus("FAILED");
                afterSaleMapper.updateById(afterSalePatch);
            }
        }
        if (order != null) {
            if (refund.getAfterSaleId() != null) {
                orderService.markAfterSaleRefundFailed(order);
            } else {
                orderService.markRefundFailed(order);
            }
        }
        return true;
    }

    /** 先条件推进再执行副作用，过期回调或并发落败者不会重复回补与冲正。 */
    private boolean advance(Refund refund, String status, String failReason) {
        LambdaUpdateWrapper<Refund> update = new LambdaUpdateWrapper<Refund>()
                .eq(Refund::getId, refund.getId())
                .eq(Refund::getRefundNo, refund.getRefundNo())
                .eq(Refund::getStatus, STATUS_REFUNDING)
                .set(Refund::getStatus, status);
        if (STATUS_SUCCESS.equals(status)) update.set(Refund::getCompleteTime, LocalDateTime.now());
        if (failReason != null && !failReason.isBlank()) {
            update.set(Refund::getReason, failReason.substring(0, Math.min(failReason.length(), 255)));
        }
        if (refundMapper.update(null, update) == 0) return false;
        refund.setStatus(status);
        return true;
    }

    /**
     * 从 server 获取退款归集明细。
     *
     * <p><b>为什么需要请求 server</b>：归集明细依赖 {@code settlement_record}
     * 的分账进度与 {@code yeepay_divide_detail_no}，属 finance 域数据。
     * trade-service 不持有这些表，通过内部接口索取可避免反向依赖。
     *
     * <p><b>失败不阻断退款</b>：拿不到归集明细时退款仍会发起（走平台账户），
     * 由 server 侧的对账异常记录垫付金额供运营追偿。
     * 阻断退款会让用户受损，优先级更高。
     */
    @SuppressWarnings("unchecked")
    private java.util.List<java.util.Map<String, Object>> loadDivideBackDetail(
            String orderNo, com.wuling.trade.entity.Refund refund) {
        try {
            org.springframework.web.client.RestClient client = serverInternalRestClient;
            if (client == null) {
                return null;
            }
            java.util.Map<String, Object> body = new java.util.HashMap<>();
            body.put("orderId", refund.getOrderId());
            body.put("refundAmountFen", refund.getAmount());
            body.put("refundRatio", null);
            java.util.Map<String, Object> response = client.post()
                    .uri("/internal/yeepay/refund/divide-back-detail")
                    .body(body)
                    .retrieve()
                    .body(java.util.Map.class);
            if (response == null) {
                return null;
            }
            Object detail = response.get("detail");
            if (detail instanceof java.util.List<?> list && !list.isEmpty()) {
                return (java.util.List<java.util.Map<String, Object>>) list;
            }
            return null;
        } catch (Exception e) {
            log.warn("获取退款归集明细失败，退款将走平台账户 orderNo={} err={}",
                    orderNo, e.getMessage());
            return null;
        }
    }

    private Payment paidPayment(String orderNo) {        return paymentMapper.selectOne(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getOrderNo, orderNo)
                .eq(Payment::getStandardStatus, "PAID")
                .and(query -> query.eq(Payment::getBizType, Payment.BIZ_ORDER).or().isNull(Payment::getBizType))
                .orderByDesc(Payment::getId).last("limit 1"));
    }

    void markFailedForTest(Refund refund, String failReason) {
        markFailed(refund, failReason);
    }

    private void validateRefundable(Order order) {
        if ("MALL".equalsIgnoreCase(order.getOrderType())) {
            if (!OrderService.STATUS_PAID.equals(order.getStatus())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "商城订单仅支持发货前整单退款");
            }
        } else if (!OrderService.STATUS_PAID.equals(order.getStatus())
                && !OrderService.STATUS_COMPLETED.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单状态不可退款: " + order.getStatus());
        }
        if ("PENDING".equalsIgnoreCase(order.getRefundStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单退款处理中，请勿重复申请");
        }
    }
    private List<OrderItem> loadItems(Long orderId) {
        return orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderId));
    }

    private String nextNo() {
        return "RF" + System.currentTimeMillis() % 100000000L
                + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
    }
    /**
     * 退款状态主动同步结果（后台运维入口返回）。
     *
     * @param refundNo 商户退款单号
     * @param orderNo  原商户订单号
     * @param status   查询并推进后的最新退款单状态（REFUNDING/SUCCESS/FAILED）
     * @param advanced 本次是否真正推进了状态（首次从 REFUNDING 变为终态）
     */
    public record RefundSyncResult(String refundNo, String orderNo, String status, boolean advanced) {
    }
}
