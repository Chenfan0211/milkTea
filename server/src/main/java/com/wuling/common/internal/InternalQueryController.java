package com.wuling.common.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.finance.entity.SettlementRecord;
import com.wuling.finance.mapper.SettlementRecordMapper;
import com.wuling.subject.entity.BizSubject;
import com.wuling.subject.mapper.BizSubjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务内部查询接口（第 7 期新增，第 13 期随 product 迁移而收窄）。
 *
 * <p>背景：trade 拆分为独立服务后，失去了对 subject / finance 表的
 * 直接访问能力。但它仍需要：
 * <ol>
 *   <li>下单时填充门店名称（{@code /subject-name}）；</li>
 *   <li>退款前<b>同步</b>判断订单是否已结算（防资金穿透，无法异步）。</li>
 * </ol>
 *
 * <p><b>第 13 期变更</b>：原 {@code /internal/product} 与
 * {@code /internal/product-in-store} 已迁至 product-service
 * （见 {@code ProductInternalQueryController}）。商品拆出后若仍由 server 提供，
 * 会形成 trade → server → product 的绕行且 server 反向依赖 product 域。
 *
 * <p><b>安全约束（重要）</b>：
 * <ul>
 *   <li>{@code /internal/**} <b>不得</b>经网关对外暴露 —— 网关仅路由
 *       {@code /api/v1/**} 与 {@code /auth/**}，本前缀天然不在其中；</li>
 *   <li>各服务仅监听 127.0.0.1，跨服务调用走内网/本机，不经公网；</li>
 *   <li>接口只读，不提供任何写操作。</li>
 * </ul>
 *
 * <p>后续若引入网关统一鉴权，需为内部调用增加服务间凭证（如 mTLS 或内部 token）。
 */
@RestController
@RequestMapping("/internal")
public class InternalQueryController {

    /** 结算状态：可结算（钱已进可用余额） */
    private static final String SETTLE_SETTLEABLE = "SETTLEABLE";
    /** 结算状态：已结算 */
    private static final String SETTLE_SETTLED = "SETTLED";

    private final BizSubjectMapper bizSubjectMapper;
    private final SettlementRecordMapper settlementRecordMapper;

    public InternalQueryController(BizSubjectMapper bizSubjectMapper,
                                   SettlementRecordMapper settlementRecordMapper) {
        this.bizSubjectMapper = bizSubjectMapper;
        this.settlementRecordMapper = settlementRecordMapper;
    }

    /**
     * 查询主体名称（供 trade 填充门店名、校验门店存在）。
     *
     * <p>subject 当前未拆为独立服务（第 12 期决策：其被 finance 事务内调用，
     * 远程化会引入「事务内网络调用」的资金风险），故仍由 server 提供。
     *
     * @return { "name": "..." }；不存在时 name 为 null
     */
    @GetMapping("/subject-name")
    public Map<String, Object> subjectName(@RequestParam Long subjectId) {
        BizSubject subject = bizSubjectMapper.selectById(subjectId);
        Map<String, Object> result = new HashMap<>();
        result.put("name", subject == null ? null : subject.getName());
        return result;
    }

    /**
     * 查询订单结算状态（供 trade 退款前置校验）。
     *
     * <p><b>为什么必须是同步接口</b>：退款受理前必须确认订单尚未进入
     * 可结算/已结算状态，否则会出现「钱已进可用余额却仍被退款」的资金穿透。
     * 该判断无法用异步事件替代。
     *
     * @param orderId 订单 ID
     * @return { "settled": true/false, "hasSettlement": true/false }
     */
    @GetMapping("/settlement-status")
    public Map<String, Object> settlementStatus(@RequestParam Long orderId) {
        List<SettlementRecord> records = settlementRecordMapper.selectList(
                new LambdaQueryWrapper<SettlementRecord>().eq(SettlementRecord::getOrderId, orderId));
        boolean settled = records.stream()
                .anyMatch(r -> SETTLE_SETTLEABLE.equals(r.getStatus()) || SETTLE_SETTLED.equals(r.getStatus()));
        return Map.of(
                "settled", settled,
                "hasSettlement", !records.isEmpty()
        );
    }
}