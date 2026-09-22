package com.wuling.common.mq;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * DLQ 声明一致性测试（第 10 期）。
 *
 * <p>背景：第 5、6 期新增业务队列时，只在队列上配了
 * {@code x-dead-letter-routing-key}，却漏了声明对应的 DLQ 队列。
 * 后果是死信消息被 RabbitMQ 静默丢弃（路由不存在），
 * 且运维无法从 DLQ 查到失败消息 —— 而这几个队列承载的正是资金链路。
 *
 * <p><b>校验规则（严格）</b>：每个 {@code *_DLQ} 常量都必须出现在
 * {@code QueueBuilder.durable(MqConstants.XXX_DLQ)} 中 ——
 * 即真正被声明成了队列，而不仅仅是出现在某个参数里。
 *
 * <p>为什么不用「是否包含常量名」这种宽松判断：
 * 早期版本用 contains 检查，把 {@code .with(MqConstants.XXX_DLQ)} 也算通过，
 * 结果漏声明队列的情况仍能蒙混过关（已实测该缺陷）。
 */
class DlqDeclarationTest {

    private Path repoRoot() {
        return Paths.get("..").toAbsolutePath().normalize();
    }

    private Path constantsPath() {
        return repoRoot().resolve("wuling-common/src/main/java/com/wuling/common/mq/MqConstants.java");
    }

    private Path configPath() {
        return repoRoot().resolve("wuling-common/src/main/java/com/wuling/common/mq/RabbitConfig.java");
    }

    /** 提取源码中所有被「声明为 durable 队列」的常量名 */
    private List<String> declaredQueueConstants(String configSrc) {
        Matcher m = Pattern.compile("QueueBuilder\\.durable\\(MqConstants\\.(\\w+)\\)").matcher(configSrc);
        List<String> names = new ArrayList<>();
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    @Test
    void everyDlqConstantMustBeDeclaredAsDurableQueue() throws IOException {
        if (!Files.exists(constantsPath()) || !Files.exists(configPath())) {
            fail("未找到 MqConstants 或 RabbitConfig");
        }
        String constSrc = Files.readString(constantsPath());
        String configSrc = Files.readString(configPath());

        Matcher cm = Pattern.compile("String\\s+(\\w+_DLQ)\\s*=").matcher(constSrc);
        List<String> dlqNames = new ArrayList<>();
        while (cm.find()) {
            dlqNames.add(cm.group(1));
        }
        assertTrue(!dlqNames.isEmpty(), "未提取到 *_DLQ 常量，测试可能失效");

        List<String> declared = declaredQueueConstants(configSrc);

        List<String> missing = new ArrayList<>();
        for (String name : dlqNames) {
            if (!declared.contains(name)) {
                missing.add(name);
            }
        }

        assertTrue(missing.isEmpty(),
                "以下死信队列在 MqConstants 中定义，但未声明为 durable 队列"
                        + "（死信将被静默丢弃）: " + missing
                        + "\n请补 QueueBuilder.durable(MqConstants.XXX_DLQ) 并绑定到 dlxExchange()");
    }

    @Test
    void everyDlqMustBeBoundToDlxExchange() throws IOException {
        if (!Files.exists(constantsPath()) || !Files.exists(configPath())) {
            fail("未找到 MqConstants 或 RabbitConfig");
        }
        String constSrc = Files.readString(constantsPath());
        String configSrc = Files.readString(configPath());

        Matcher cm = Pattern.compile("String\\s+(\\w+_DLQ)\\s*=").matcher(constSrc);
        List<String> dlqNames = new ArrayList<>();
        while (cm.find()) {
            dlqNames.add(cm.group(1));
        }

        // 绑定形式：.with(MqConstants.XXX_DLQ)
        Matcher bm = Pattern.compile("\\.with\\(MqConstants\\.(\\w+_DLQ)\\)").matcher(configSrc);
        List<String> bound = new ArrayList<>();
        while (bm.find()) {
            bound.add(bm.group(1));
        }

        List<String> unbound = dlqNames.stream().filter(n -> !bound.contains(n)).toList();
        assertTrue(unbound.isEmpty(),
                "以下死信队列未绑定到死信交换机（死信无法投递）: " + unbound);
    }

    @Test
    void everyBusinessQueueWithDeadLetterKeyMustHaveDlqDeclared() throws IOException {
        if (!Files.exists(constantsPath()) || !Files.exists(configPath())) {
            fail("未找到 MqConstants 或 RabbitConfig");
        }
        String constSrc = Files.readString(constantsPath());
        String configSrc = Files.readString(configPath());

        // 业务队列常量名（排除 *_DLQ）
        Matcher qm = Pattern.compile("String\\s+(\\w+_QUEUE)\\s*=\\s*\"").matcher(constSrc);
        List<String> businessQueues = new ArrayList<>();
        while (qm.find()) {
            businessQueues.add(qm.group(1));
        }
        assertTrue(!businessQueues.isEmpty(), "未提取到业务队列常量，测试可能失效");

        List<String> declared = declaredQueueConstants(configSrc);

        List<String> missing = new ArrayList<>();
        for (String q : businessQueues) {
            String dlqName = q.replace("_QUEUE", "_DLQ");
            // 该业务队列定义了 DLQ 常量，但 DLQ 未被声明为队列
            boolean dlqDefined = constSrc.contains("String " + dlqName + " =");
            if (dlqDefined && !declared.contains(dlqName)) {
                missing.add(q + " -> " + dlqName);
            }
        }

        assertTrue(missing.isEmpty(),
                "以下业务队列配置了死信但 DLQ 未声明为队列: " + missing);
    }
}
