package com.wuling.trade.port;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * {@link MemberLevelPort} 的远程实现（第 15 期：会员价后端重算）。
 *
 * <p><b>数据来源分两处</b>（会员等级天然跨两个域）：
 * <ul>
 *   <li>用户等级代码 → user-service 的 {@code /internal/users/{id}/vip-level}
 *       （权威来源是 {@code app_user.vip_level}）；</li>
 *   <li>等级对应折扣 → marketing-service 的
 *       {@code /internal/member-levels/{code}/discount}（{@code member_level} 表）。</li>
 * </ul>
 * 两者都是内部接口，不经网关、不对外暴露。
 *
 * <p><b>失败语义</b>：查询失败一律返回 null，由 {@code MemberPricingService}
 * 按「无等级 / 不打折」处理。<b>刻意不抛异常</b>——等级查询故障不应该让整单下不了，
 * 而「不打折」是安全的失败方向（最坏是用户没享受到折扣，平台不会少收钱）。
 *
 * <p><b>安全语义</b>：本实现返回的是服务端权威值。交易侧绝不采信客户端传入的等级，
 * 否则用户改包成高等级即可自选折扣。
 */
@Component
public class RemoteMemberLevelAdapter implements MemberLevelPort {

    private static final Logger log = LoggerFactory.getLogger(RemoteMemberLevelAdapter.class);

    private final RestClient userRestClient;
    private final RestClient marketingRestClient;

    public RemoteMemberLevelAdapter(
            @Qualifier("userInternalRestClient") RestClient userRestClient,
            @Qualifier("marketingInternalRestClient") RestClient marketingRestClient) {
        this.userRestClient = userRestClient;
        this.marketingRestClient = marketingRestClient;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String findUserLevelCode(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            Map<String, Object> body = userRestClient.get()
                    .uri("/internal/users/{id}/vip-level", userId)
                    .retrieve()
                    .body(Map.class);
            if (body == null || !Boolean.TRUE.equals(body.get("found"))) {
                log.info("用户未设置会员等级 userId={}", userId);
                return null;
            }
            Object level = body.get("vipLevel");
            return level == null ? null : String.valueOf(level);
        } catch (Exception e) {
            log.error("远程查询用户会员等级失败 userId={} err={}", userId, e.getMessage());
            return null;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public String findLevelDiscount(String levelCode) {
        if (levelCode == null || levelCode.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> body = marketingRestClient.get()
                    .uri("/internal/member-levels/{code}/discount", levelCode)
                    .retrieve()
                    .body(Map.class);
            if (body == null || !Boolean.TRUE.equals(body.get("found"))) {
                log.info("会员等级折扣不存在 levelCode={}", levelCode);
                return null;
            }
            Object discount = body.get("discount");
            return discount == null ? null : String.valueOf(discount);
        } catch (Exception e) {
            log.error("远程查询会员折扣失败 levelCode={} err={}", levelCode, e.getMessage());
            return null;
        }
    }
}