package com.huawei.hisi.service.intent;

/**
 * 用户意图类型枚举
 * 定义自然语言输入可识别的意图类别
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
public enum IntentType {

    /**
     * 诊断日志意图
     * 用户请求分析错误日志、堆栈信息等
     * 例: "帮我分析这个NPE错误", "查看日志报错原因"
     */
    DIAGNOSE_LOG("诊断日志", "分析错误日志、堆栈追踪"),

    /**
     * 查询代码意图
     * 用户请求查找、搜索代码相关内容
     * 例: "查找UserService的实现", "搜索login方法"
     */
    QUERY_CODE("查询代码", "搜索代码、查找方法实现"),

    /**
     * 解释错误意图
     * 用户请求解释某个错误或异常的含义
     * 例: "解释这个TimeoutException", "什么是NullPointerException"
     */
    EXPLAIN_ERROR("解释错误", "解释错误含义、异常类型"),

    /**
     * 用户干预意图
     * 用户主动提供额外信息或调整分析方向
     * 例: "只关注这个类", "换一个分析角度", "忽略之前的结论"
     */
    INTERVENE("用户干预", "调整分析方向、提供额外信息"),

    /**
     * 追问意图
     * 用户对之前的结论进行追问
     * 例: "为什么会这样?", "有没有其他原因?", "详细解释一下"
     */
    FOLLOW_UP("追问", "对结论进行追问、要求详细解释"),

    /**
     * 未知意图
     * 无法识别的用户输入
     */
    UNKNOWN("未知意图", "无法识别的输入");

    private final String displayName;
    private final String description;

    IntentType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 是否为诊断类意图（需要触发Agent诊断）
     */
    public boolean isDiagnosticIntent() {
        return this == DIAGNOSE_LOG || this == EXPLAIN_ERROR;
    }

    /**
     * 是否需要对话上下文支持
     */
    public boolean needsContext() {
        return this == FOLLOW_UP || this == INTERVENE;
    }
}