package com.huawei.hisi.model;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 应用配置模型类
 * 对应 app_config 数据库表
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
public class AppConfig {
    /**
     * 配置键（主键）
     */
    private String key;

    /**
     * 配置值
     */
    private String value;

    /**
     * 配置描述
     */
    private String description;

    /**
     * 最后更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 最后更新者
     */
    private String updatedBy;
}