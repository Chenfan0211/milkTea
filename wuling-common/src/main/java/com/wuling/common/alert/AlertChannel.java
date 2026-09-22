package com.wuling.common.alert;

/**
 * 告警通道（第 9 期）。
 *
 * <p>为什么抽接口：告警的「发送方式」会变（当前写日志，将来可能接邮件/企微机器人），
 * 但「什么时候告警」是业务逻辑（如对账发现资金不一致）。
 * 把发送方式抽象出来，新增通道只需加实现类 + 配置，不改业务代码。
 *
 * <p>实现约束：<b>告警失败绝不能影响业务</b>。
 * 所有实现必须自行吞掉异常（记录到日志即可），不得向外抛出 ——
 * 否则一次告警发送失败可能回滚掉对账记录这一事实。
 */
public interface AlertChannel {

    /** 告警级别 */
    enum Level {
        /** 需要立即人工介入（如资金不一致） */
        CRITICAL,
        /** 需要关注（如死信堆积） */
        WARNING,
        /** 一般信息 */
        INFO
    }

    /**
     * 发送告警。
     *
     * @param level   级别
     * @param title   标题（简短，便于监控抓取）
     * @param detail  详情
     * @param bizKey  业务键（如订单号），便于检索
     */
    void send(Level level, String title, String detail, String bizKey);

    /** 通道标识（用于日志与排查） */
    String channelName();
}
