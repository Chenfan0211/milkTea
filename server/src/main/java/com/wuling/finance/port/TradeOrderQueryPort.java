package com.wuling.finance.port;

import java.util.List;
import java.util.Map;

/**
 * 交易域订单查询端口（第 7 期）。
 *
 * <p>背景：finance 的角色工作台需要按门店/渠道统计订单，
 * 而这些数据归属 trade。trade 拆出后不能再直连其表。
 *
 * <p>抽成端口后：finance 只依赖本接口，实现可切换为远程调用，
 * 业务代码不感知数据来源变化。
 */
public interface TradeOrderQueryPort {

    /** 门店今日订单数 */
    long countStoreTodayOrders(Long storeSubjectId);

    /** 门店订单列表（字段为调用方所需的最小集） */
    List<Map<String, Object>> storeOrders(Long storeSubjectId);

    /** 按门店集合查询订单（渠道视角） */
    List<Map<String, Object>> ordersByStores(List<Long> storeSubjectIds);
}
