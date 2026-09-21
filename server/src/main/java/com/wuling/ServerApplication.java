package com.wuling;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan({
        "com.wuling.auth.mapper",
        "com.wuling.user.mapper",
        "com.wuling.subject.mapper",
        "com.wuling.product.mapper",
        "com.wuling.system.mapper",
        "com.wuling.trade.mapper",
        "com.wuling.finance.mapper",
        "com.wuling.marketing.mapper"
})
public class ServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServerApplication.class, args);
    }
}
