package com.wuling.marketing.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.marketing.entity.MemberLevel;
import com.wuling.marketing.mapper.MemberLevelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 会员等级折扣内部接口（第 15 期：会员价后端重算）。
 *
 * <p><b>调用方</b>：trade-service 在下单时重算会员价。
 *
 * <p><b>为什么需要它</b>：会员价 = 商品原价 × 等级折扣，
 * 而「等级 → 折扣」的权威数据是本服务的 {@code member_level} 表。
 * trade 域不应直连营销库（跨域共享表结构），故走本内部接口。
 *
 * <p><b>安全约束</b>：{@code /internal/**} 不在网关路由范围内
 * （网关仅路由 {@code /api/v1/**} 与 {@code /auth/**}），
 * 且本服务仅监听内网/本机，因此不会经公网暴露。
 *
 * <p><b>为什么只按代码查、不返回全表</b>：折扣直接决定实收金额，
 * 只暴露「单次查询单档」可减少误用；也避免把权益等无关字段带进交易链路。
 */
@RestController
@RequestMapping("/internal/member-levels")
public class MemberLevelInternalController {

    private static final Logger log = LoggerFactory.getLogger(MemberLevelInternalController.class);

    private final MemberLevelMapper memberLevelMapper;

    public MemberLevelInternalController(MemberLevelMapper memberLevelMapper) {
        this.memberLevelMapper = memberLevelMapper;
    }

    /**
     * 按等级代码查询折扣配置原文（如 {@code Lv1} → {@code 8折}）。
     *
     * <p>返回结构固定含 {@code found}：调用方据此区分
     * 「等级不存在」与「查询失败」，避免把故障当作「不打折」静默吞掉。
     * 注意 {@code found=false} 时交易侧按原价计价（安全兜底，绝不误打折）。
     */
    @GetMapping("/{levelCode}/discount")
    public Map<String, Object> discount(@PathVariable String levelCode) {
        Map<String, Object> result = new HashMap<>();
        result.put("levelCode", levelCode);
        try {
            MemberLevel level = memberLevelMapper.selectOne(
                    new LambdaQueryWrapper<MemberLevel>()
                            .eq(MemberLevel::getLevelCode, levelCode)
                            .last("LIMIT 1"));
            if (level == null) {
                log.info("内部查询会员等级不存在 levelCode={}", levelCode);
                result.put("discount", null);
                result.put("found", false);
                return result;
            }
            result.put("discount", level.getDiscount());
            result.put("name", level.getName());
            result.put("found", true);
        } catch (Exception e) {
            // 查询失败：found=false，由调用方按「不打折」安全兜底
            log.error("内部查询会员折扣失败 levelCode={} err={}", levelCode, e.getMessage());
            result.put("discount", null);
            result.put("found", false);
        }
        return result;
    }
}