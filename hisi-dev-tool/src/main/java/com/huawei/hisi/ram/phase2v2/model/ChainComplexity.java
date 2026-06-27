// hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/model/ChainComplexity.java
package com.huawei.hisi.ram.phase2v2.model;

/**
 * 链路复杂度分级，用于动态分配工具权限。
 */
public enum ChainComplexity {
    /** 单服务单模块链路 - 最小工具集 */
    SIMPLE,

    /** 跨服务 Feign/MQ 链路 - 增加 WebFetch */
    CROSS_SERVICE,

    /** 领域级复杂分析 - 增加 Bash */
    DOMAIN_ANALYSIS,

    /** 需要编译/测试验证 - 增加 Agent (嵌套) */
    VERIFICATION
}