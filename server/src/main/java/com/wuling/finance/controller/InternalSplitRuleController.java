package com.wuling.finance.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.finance.entity.SplitRule;
import com.wuling.finance.mapper.SplitRuleMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Internal immutable split-rule lookup used by trade-service at mall order creation. */
@RestController
@RequestMapping("/internal/split-rule")
public class InternalSplitRuleController {
    private final SplitRuleMapper splitRuleMapper;

    public InternalSplitRuleController(SplitRuleMapper splitRuleMapper) {
        this.splitRuleMapper = splitRuleMapper;
    }

    @GetMapping("/{id}")
    public Map<String, Object> byId(@PathVariable Long id) {
        return view(splitRuleMapper.selectById(id));
    }

    @GetMapping("/current")
    public Map<String, Object> current() {
        return view(splitRuleMapper.selectOne(new LambdaQueryWrapper<SplitRule>()
                .eq(SplitRule::getScope, "GLOBAL")
                .eq(SplitRule::getStatus, "enabled")
                .orderByAsc(SplitRule::getId)
                .last("limit 1")));
    }

    private Map<String, Object> view(SplitRule rule) {
        if (rule == null || !"enabled".equalsIgnoreCase(rule.getStatus())) {
            return Map.of("found", false);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", true);
        result.put("ruleId", rule.getId());
        result.put("storeRatio", rule.getStoreRatio());
        result.put("channelRatio", rule.getChannelRatio());
        result.put("investorRatio", rule.getInvestorRatio());
        result.put("investorThresholdAmount", rule.getInvestorThresholdAmount());
        result.put("investorRatioAfter", rule.getInvestorRatioAfter());
        return result;
    }
}
