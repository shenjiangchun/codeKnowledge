package com.huawei.hisi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Proxy metadata model for storing information about proxy classes.
 * Covers MyBatis Mapper, JPA Repository, Spring AOP Aspect, and JDK/CGLIB proxies.
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProxyMetadata {

    /**
     * Interface or class name (full qualified)
     */
    private String interfaceName;

    /**
     * Interface type: MYBATIS, JPA, AOP, JDK_PROXY, CGLIB
     */
    private InterfaceType interfaceType;

    /**
     * Implementation class name (if known)
     */
    private String implementation;

    /**
     * Proxy type: MYBATIS_MAPPER, JPA_REPOSITORY, ASPECT, JDK_DYNAMIC, CGLIB
     */
    private ProxyType proxyType;

    /**
     * Method name
     */
    private String methodName;

    /**
     * Method signature (full signature with parameters)
     */
    private String methodSignature;

    /**
     * SQL statement (for MyBatis mapper methods)
     */
    private String sqlStatement;

    /**
     * SQL operation type: SELECT, INSERT, UPDATE, DELETE
     */
    private String sqlOperationType;

    /**
     * Entity class type (for JPA repository)
     */
    private String entityType;

    /**
     * Entity ID type (for JPA repository)
     */
    private String entityIdType;

    /**
     * Pointcut expression (for AOP aspects)
     */
    private String pointcutExpression;

    /**
     * Advice type: BEFORE, AFTER, AROUND, AFTER_RETURNING, AFTER_THROWING
     */
    private String adviceType;

    /**
     * Project package name
     */
    private String packageName;

    /**
     * Additional metadata as JSON string
     */
    private String metadata;

    /**
     * Interface type enumeration
     */
    public enum InterfaceType {
        MYBATIS,
        JPA,
        AOP,
        JDK_PROXY,
        CGLIB
    }

    /**
     * Proxy type enumeration
     */
    public enum ProxyType {
        MYBATIS_MAPPER,
        JPA_REPOSITORY,
        ASPECT,
        JDK_DYNAMIC,
        CGLIB
    }
}