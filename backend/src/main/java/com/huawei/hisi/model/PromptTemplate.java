package com.huawei.hisi.model;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 提示词模板实体
 */
@Data
public class PromptTemplate {
    /** 模板键 (主键): log-analysis, code-analysis, trace-analysis, impact-analysis, free-chat */
    private String templateKey;

    /** 模板名称 */
    private String name;

    /** 提示词内容，支持 #{变量名} 格式 */
    private String content;

    /** 变量列表 (JSON数组字符串) */
    private String variables;

    /** 场景描述 */
    private String description;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
