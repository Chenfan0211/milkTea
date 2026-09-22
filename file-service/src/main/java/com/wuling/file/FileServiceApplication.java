package com.wuling.file;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 文件服务启动类（第 2 期新增）。
 *
 * 独立端口 8082。当前阶段只提供上传校验能力，
 * 对象存储与病毒扫描在后续阶段接入。
 */
@SpringBootApplication
@EnableDiscoveryClient
public class FileServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileServiceApplication.class, args);
    }
}
