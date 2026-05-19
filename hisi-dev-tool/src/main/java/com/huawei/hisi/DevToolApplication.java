package com.huawei.hisi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Java LLM API Demo 主应用类
 *
 * @author Java LLM API Demo
 */
@SpringBootApplication
@EnableCaching
@EnableScheduling
public class DevToolApplication {

    public static void main(String[] args) {
        SpringApplication.run(DevToolApplication.class, args);
    }

}