package com.huawei.hisi.knowledgegraph.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 代理元数据模型
 * 表示代理类的元数据信息 (MyBatis Mapper/JPA Repository/AOP)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProxyMetadata {
    /**
     * 主键 ID
     */
    private Long id;

    /**
     * 接口全限定名
     */
    private String interfaceName;

    /**
     * 接口类型: MYBATIS/JPA/AOP/JDK_PROXY/CGLIB
     */
    private String interfaceType;

    /**
     * 实现类 (如果已知)
     */
    private String implementation;

    /**
     * 代理类型: MYBATIS_MAPPER/JPA_REPOSITORY/ASPECT/JDK_DYNAMIC/CGLIB
     */
    private String proxyType;

    /**
     * 方法名
     */
    private String methodName;

    /**
     * 方法签名
     */
    private String methodSignature;

    /**
     * SQL 语句 (用于 MyBatis)
     */
    private String sqlStatement;

    /**
     * 实体类 (用于 JPA)
     */
    private String entityType;

    /**
     * 所属项目路径
     */
    private String projectPath;

    /**
     * 附加元数据 (JSON 格式)
     */
    private String metadata;

    // 接口类型常量
    public static final String INTERFACE_TYPE_MYBATIS = "MYBATIS";
    public static final String INTERFACE_TYPE_JPA = "JPA";
    public static final String INTERFACE_TYPE_AOP = "AOP";
    public static final String INTERFACE_TYPE_JDK_PROXY = "JDK_PROXY";
    public static final String INTERFACE_TYPE_CGLIB = "CGLIB";

    // 代理类型常量
    public static final String PROXY_TYPE_MYBATIS_MAPPER = "MYBATIS_MAPPER";
    public static final String PROXY_TYPE_JPA_REPOSITORY = "JPA_REPOSITORY";
    public static final String PROXY_TYPE_ASPECT = "ASPECT";
    public static final String PROXY_TYPE_JDK_DYNAMIC = "JDK_DYNAMIC";
    public static final String PROXY_TYPE_CGLIB = "CGLIB";
}
