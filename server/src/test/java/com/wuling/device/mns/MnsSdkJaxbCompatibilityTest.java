package com.wuling.device.mns;

import com.aliyun.mns.common.parser.JAXBResultParser;
import com.aliyun.mns.model.ErrorMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MNS SDK 的 JAXB 依赖可用性守卫。
 *
 * <p><b>为什么需要这个测试</b>：MNS SDK 用 JAXB 2.x（{@code javax.xml.bind}）
 * 解析错误响应，而 JDK 17 已移除 JAXB、Spring Boot 3.3.x BOM 管理的又是
 * 4.x（{@code jakarta.xml.bind}）——两者包名不同、不能互相顶替。
 * 一旦依赖被顶错，平时相安无事，一旦对方返回错误（密钥错、无权限、队列不存在）
 * 就会抛 NoClassDefFoundError，连错误原因都看不到。
 *
 * <p>该用例走的正是 SDK 生产路径（ExceptionResultParser -> JAXBResultParser），
 * 因此能在 CI 阶段就拦住「依赖版本被 BOM 顶掉」这类问题。
 */
class MnsSdkJaxbCompatibilityTest {

    @Test
    void parsesMnsErrorResponseThroughSdkParser() throws Exception {
        JAXBResultParser parser = new JAXBResultParser(ErrorMessage.class);

        // 命名空间必须带上：SDK 的 ErrorMessage 标注的是
        // @XmlRootElement(name="Error", namespace="http://mns.aliyuncs.com/doc/v1")，
        // 这也正是 MNS 返回错误响应时的报文格式。
        Object parsed = parser.parse("""
                <Error xmlns="http://mns.aliyuncs.com/doc/v1">
                  <Code>AccessDenied</Code>
                  <Message>The AccessKeyId is disabled.</Message>
                  <RequestId>5F1B2C3D4E5F6071</RequestId>
                  <Method>GET</Method>
                  <HostId>1862421895270680.mns.cn-shanghai.aliyuncs.com</HostId>
                </Error>
                """);

        assertEquals(ErrorMessage.class, parsed.getClass());
        ErrorMessage error = (ErrorMessage) parsed;
        assertEquals("AccessDenied", error.Code);
        assertEquals("1862421895270680.mns.cn-shanghai.aliyuncs.com", error.HostId);
    }
}
