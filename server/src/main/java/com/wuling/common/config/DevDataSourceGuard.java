package com.wuling.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;

/**
 * 开发环境数据源守卫（防止本地误改生产数据）。
 *
 * <p>背景：第 0 期曾发生「本地开发经 SSH 隧道直连生产库，
 * 误将迁移应用到生产」的事故。事后采用三层防护：
 * <ol>
 *   <li><b>数据库层</b>：开发使用只读账号 {@code wuling_ro}，MySQL 直接拒绝写操作；</li>
 *   <li><b>应用层</b>：dev profile 关闭 Flyway，本地不执行迁移；</li>
 *   <li><b>提示层</b>：本类在启动完成后检查数据源指向，连到疑似生产地址时打印醒目警告。</li>
 * </ol>
 *
 * <p>前两层是硬约束（拒绝执行），第三层是软提醒（帮助开发者在第一时间意识到风险）。
 *
 * <p>仅在 dev profile 生效，不影响生产。
 */
@Configuration
@Profile("dev")
public class DevDataSourceGuard {

    private static final Logger log = LoggerFactory.getLogger(DevDataSourceGuard.class);

    /** 生产库的典型标识（内网 IP / 域名 / 非隧道端口） */
    private static final String[] PROD_HINTS = {"172.16.0.16", "43.136.91.239", "wulingshiguang.top"};

    private final String datasourceUrl;
    private final String username;

    public DevDataSourceGuard(@Value("${spring.datasource.url:}") String datasourceUrl,
                              @Value("${spring.datasource.username:}") String username) {
        this.datasourceUrl = datasourceUrl;
        this.username = username;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        boolean pointsToProd = false;
        for (String hint : PROD_HINTS) {
            if (datasourceUrl != null && datasourceUrl.contains(hint)) {
                pointsToProd = true;
                break;
            }
        }

        if (pointsToProd) {
            log.warn("================================================================");
            log.warn("⚠ 开发环境正在连接【生产数据库】: {}", datasourceUrl);
            log.warn("  当前账号: {}", username);
            log.warn("  请确认该账号为只读账号(wuling_ro)，否则可能误改生产数据！");
            log.warn("  建议尽快搭建本地独立库，彻底隔离（见 docs/数据库隔离方案.md）");
            log.warn("================================================================");
        } else {
            log.info("开发环境数据源: {} (账号 {})", datasourceUrl, username);
        }

        if ("wuling".equals(username)) {
            log.warn("⚠ 开发环境使用了【可写账号 wuling】。");
            log.warn("  请改用只读账号 wuling_ro（MYSQL_USER=wuling_ro），避免误改生产数据。");
        }
    }
}
