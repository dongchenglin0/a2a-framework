package com.fox.a2a.registry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * A2A Registry 服务启动类
 * 负责 Agent 的注册、发现、心跳管理
 */
@SpringBootApplication
@EnableScheduling
public class RegistryApplication {

    public static void main(String[] args) {
        SpringApplication.run(RegistryApplication.class, args);
    }
}
