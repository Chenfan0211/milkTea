package com.wuling.common.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 日志告警通道（当前唯一实现）。
 *
 * <p>当前生产环境 25 端口被封（云服务器常见），无法直投外部邮箱；
 * 465/587 可达，接 SMTP 需邮件账号授权码。经决策先采用日志通道。
 *
 * <p>为弥补「纯日志容易被忽视」的短板，本实现做三件事：
 * <ol>
 *   <li>写入独立告警文件（{@code app.alert.log-file}），不混在业务日志里；</li>
 *   <li>同步打 ERROR 日志，便于日志采集系统（如后续接入的采集器）捕获；</li>
 *   <li>维护一个「当前是否存在未处理告警」的标记文件，
 *       供运维脚本 {@code alert-check.sh} 快速判断（无需解析日志）。</li>
 * </ol>
 *
 * <p>升级路径：接入 SMTP / 企微机器人时新增一个 {@link AlertChannel} 实现，
 * 用 {@code @Primary} 或条件注解切换即可，本类保留为兜底通道。
 */
@Component
public class LogAlertChannel implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger(LogAlertChannel.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path alertFile;
    private final Path flagFile;

    public LogAlertChannel(
            @Value("${app.alert.log-file:/opt/wuling/logs/alerts.log}") String alertFile,
            @Value("${app.alert.flag-file:/opt/wuling/logs/ALERT_PENDING}") String flagFile) {
        this.alertFile = Paths.get(alertFile);
        this.flagFile = Paths.get(flagFile);
    }

    @Override
    public void send(Level level, String title, String detail, String bizKey) {
        String line = String.format("[%s] [%s] %s | %s | bizKey=%s",
                LocalDateTime.now().format(FMT), level, title,
                detail == null ? "" : detail, bizKey == null ? "-" : bizKey);

        // 1) 同步打日志：CRITICAL 用 ERROR，便于采集系统按级别告警
        if (level == Level.CRITICAL) {
            log.error("ALERT {}", line);
        } else {
            log.warn("ALERT {}", line);
        }

        // 2) 写独立告警文件 + 3) 维护待处理标记
        // 整体 try-catch：告警失败绝不影响业务（接口约定）
        try {
            writeLine(alertFile, line);
            if (level == Level.CRITICAL) {
                writeLine(flagFile, line);
            }
        } catch (Exception e) {
            log.warn("写告警文件失败（不影响业务）: {}", e.getMessage());
        }
    }

    private void writeLine(Path path, String line) throws IOException {
        Path parent = path.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        // 追加写，并用 CREATE 避免文件不存在时报错
        Files.write(path, (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Override
    public String channelName() {
        return "log";
    }
}
