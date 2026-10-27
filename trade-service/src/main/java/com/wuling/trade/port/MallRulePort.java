package com.wuling.trade.port;

import com.wuling.common.mq.event.SplitRuleSnapshot;

public interface MallRulePort {
    SplitRuleSnapshot snapshot(Long ruleId);
}
