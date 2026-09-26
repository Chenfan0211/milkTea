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
    /**
     * 用户角色授权表（user_role_grant）与 app_user 归属 user-service，
     * server 模块不依赖其 Java 实体，跨模块读表统一走 JdbcTemplate
     * （与 AppRoleController 的做法一致）。
     */
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public InternalQueryController(BizSubjectMapper bizSubjectMapper,
                                   SettlementRecordMapper settlementRecordMapper,
                                   org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.bizSubjectMapper = bizSubjectMapper;
        this.settlementRecordMapper = settlementRecordMapper;
        this.jdbcTemplate = jdbcTemplate;
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
     * 校验「用户是否为某门店主体的有效经营者」。
     *
     * <p><b>为什么需要</b>：小程序端门店核销接口必须确认调用者确实经营该门店，
     * 否则任何登录用户都能传任意 {@code storeSubjectId} 核销他人门店的订单
     * （越权核销 = 订单被他人放行、分账流向错误门店）。
     * 该判断必须在服务端完成，不能信任前端传入的主体 ID。
     *
     * <p><b>口径</b>：满足其一即视为有权经营：
     * <ol>
     *   <li>{@code user_role_grant} 中存在 (user_id, role_code=STORE, subject_id, status=active) 记录；</li>
     *   <li>{@code app_user.bound_subject_id = subjectId} 且 {@code business_role = 'store'}。</li>
     * </ol>
     * 与小程序 {@code /api/v1/app/roles/mine} 的判定口径保持一致。
     *
     * @param userId    小程序用户 ID（取自 JWT）
     * @param subjectId 门店主体 ID
     * @return { "allowed": true/false }
     */
    @GetMapping("/store-operator-check")
    public Map<String, Object> storeOperatorCheck(@RequestParam Long userId,
                                                  @RequestParam Long subjectId) {
        if (userId == null || subjectId == null) {
            return Map.of("allowed", false);
        }
        // 1) 授权表：显式授予且已生效
        Long granted = jdbcTemplate.queryForObject(
                "select count(*) from user_role_grant "
                        + "where user_id = ? and role_code = 'STORE' and subject_id = ? "
                        + "and status = 'active' and deleted = 0",
                Long.class, userId, subjectId);
        if (granted != null && granted > 0) {
            return Map.of("allowed", true);
        }
        // 2) 当前绑定主体：business_role=store 且绑定到该门店
        Long bound = jdbcTemplate.queryForObject(
                "select count(*) from app_user "
                        + "where id = ? and bound_subject_id = ? and business_role = 'store' and deleted = 0",
                Long.class, userId, subjectId);
        return Map.of("allowed", bound != null && bound > 0);
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

    /**
     * 查询订单分账快照（供 trade 后台订单接口回填「分账明细」）。
     *
     * <p><b>为什么需要</b>：运营后台的订单列表/详情要展示分账明细，而
     * split_snapshot 归属 finance 域。若 trade 直接读 finance 表，会破坏
     * 服务边界（与 /internal/product 迁走的原因相同），故由 server 暴露只读查询。
     *
     * <p>快照只在订单核销后生成，未核销订单返回含 null 的占位结构（而非 404），
     * 让调用方可以无差别地判空。
     *
     * <p><b>返回口径</b>：金额单位一律「分」；costTotal = supplier_amount
     * （已是「成本单价 × 件数」的合计），platformCommission = Σ(提成单价 × 件数)。
     * base 为投资人计费基础额，快照未存该列，按
     * 「实付 − 门店 − 资源方 − 成本 − 平台提成」现算。
     *
     * @param orderNo 订单号
     * @return 快照摘要；无快照时各金额字段为 null
     */
    @GetMapping("/split-snapshot")
    public Map<String, Object> splitSnapshot(@RequestParam String orderNo) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select s.snapshot_no, s.item_count, s.supplier_amount, s.store_amount, "
                        + "s.channel_amount, s.investor_amount, s.platform_amount, s.platform_commission, "
                        + "o.paid_amount "
                        + "from split_snapshot s left join orders o on o.order_no = s.order_no "
                        + "where s.order_no = ? and s.deleted = 0 order by s.id desc limit 1",
                orderNo);
        Map<String, Object> result = new HashMap<>();
        result.put("snapshotNo", null);
        result.put("itemCount", null);
        result.put("costTotal", null);
        result.put("storeShare", null);
        result.put("channelShare", null);
        result.put("investorShare", null);
        result.put("platformCommission", null);
        result.put("platformShare", null);
        result.put("base", null);
        if (rows.isEmpty()) {
            return result;
        }
        Map<String, Object> row = rows.get(0);
        long paid = nz(row.get("paid_amount"));
        long store = nz(row.get("store_amount"));
        long channel = nz(row.get("channel_amount"));
        long supplier = nz(row.get("supplier_amount"));
        long commission = nz(row.get("platform_commission"));
        long investor = nz(row.get("investor_amount"));
        result.put("snapshotNo", row.get("snapshot_no"));
        result.put("itemCount", row.get("item_count"));
        result.put("costTotal", supplier);
        result.put("storeShare", store);
        result.put("channelShare", channel);
        result.put("investorShare", investor);
        result.put("platformCommission", commission);
        result.put("platformShare", nz(row.get("platform_amount")));
        // 快照未存投资人基础额，按同一口径现算，供前端判断「基础为负」提示
        result.put("base", paid - store - channel - supplier - commission);
        return result;
    }

    /** 空值归零（JDBC 聚合列可能为 null） */
    private long nz(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}