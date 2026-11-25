package com.wuling;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 单体后端启动类。
 *
 * <p><b>@MapperScan 必须覆盖所有 *Mapper 接口所在的包</b>。
 * MyBatis 只代理被扫描到的接口；漏掉一个包，该包下所有 Mapper 都不会注册成 bean，
 * 依赖它们的 Service 构造失败，整个应用起不来。
 * 这类问题编译期完全静默，只有启动才暴露（2026-11-25 上线即因此回滚，
 * 报错为 MerchantOnboardMapper bean not found）。
 *
 * <p>新增 Mapper 后请同步在此登记；{@code MapperScanCoverageTest} 会在
 * 测试阶段校验覆盖率，漏登记会直接失败。
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
@MapperScan({
        "com.wuling.auth.mapper",
        "com.wuling.subject.mapper",
        // 入驻与签约：MerchantOnboardMapper / MerchantMaterialMapper
        "com.wuling.subject.onboard",
        "com.wuling.system.mapper",
        "com.wuling.finance.mapper",
        // 易宝结算卡：PayoutAccountMapper
        "com.wuling.finance.payee",
        "com.wuling.device.mapper"
})
public class ServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServerApplication.class, args);
    }
}
