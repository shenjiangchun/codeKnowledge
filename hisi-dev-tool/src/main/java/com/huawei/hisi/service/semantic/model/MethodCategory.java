package com.huawei.hisi.service.semantic.model;

/**
 * 方法分类枚举
 *
 * 用于区分方法的业务类型
 */
public enum MethodCategory {

    /** 业务逻辑方法 */
    BUSINESS_LOGIC("业务逻辑"),

    /** 数据访问方法 */
    DATA_ACCESS("数据访问"),

    /** 工具方法 */
    UTIL_METHOD("工具方法"),

    /** 异常处理方法 */
    EXCEPTION_HANDLER("异常处理"),

    /** 配置/初始化方法 */
    CONFIGURATION("配置/初始化"),

    /** 测试方法 */
    TEST_METHOD("测试方法"),

    /** API接口方法 */
    API_ENDPOINT("API接口"),

    /** 其他方法 */
    OTHER("其他");

    private final String description;

    MethodCategory(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 从字符串解析分类
     */
    public static MethodCategory fromString(String value) {
        if (value == null) {
            return OTHER;
        }
        for (MethodCategory category : values()) {
            if (category.description.equals(value) || category.name().equalsIgnoreCase(value)) {
                return category;
            }
        }
        return OTHER;
    }
}