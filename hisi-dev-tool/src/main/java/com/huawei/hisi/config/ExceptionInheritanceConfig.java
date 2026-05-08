package com.huawei.hisi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 异常继承关系配置类
 *
 * 用于管理异常类型之间的继承关系，支持从配置文件加载和动态添加。
 * 解决TD-004：将异常继承关系从硬编码移至配置。
 */
@ConfigurationProperties(prefix = "exception.inheritance")
@Component
@Data
public class ExceptionInheritanceConfig {

    /**
     * 异常继承关系映射
     * key: 父异常类型全限定名
     * value: 子异常类型全限定名列表
     */
    private Map<String, List<String>> hierarchy = new ConcurrentHashMap<>();

    /**
     * 是否启用默认异常继承关系
     */
    private boolean enableDefaults = true;

    /**
     * 构造函数，初始化默认异常继承关系
     */
    public ExceptionInheritanceConfig() {
        if (enableDefaults) {
            initDefaultHierarchy();
        }
    }

    /**
     * 初始化默认的异常继承关系
     */
    private void initDefaultHierarchy() {
        // Throwable层次
        hierarchy.put("java.lang.Throwable", List.of(
                "java.lang.Error",
                "java.lang.Exception"
        ));

        // Error层次
        hierarchy.put("java.lang.Error", List.of(
                "java.lang.VirtualMachineError",
                "java.lang.LinkageError",
                "java.lang.OutOfMemoryError",
                "java.lang.StackOverflowError"
        ));

        // Exception层次
        hierarchy.put("java.lang.Exception", List.of(
                "java.lang.RuntimeException",
                "java.io.IOException",
                "java.sql.SQLException",
                "java.lang.ReflectiveOperationException",
                "java.lang.InterruptedException"
        ));

        // RuntimeException层次（非受检异常）
        hierarchy.put("java.lang.RuntimeException", List.of(
                "java.lang.NullPointerException",
                "java.lang.IllegalArgumentException",
                "java.lang.IllegalStateException",
                "java.lang.IndexOutOfBoundsException",
                "java.lang.ClassCastException",
                "java.lang.UnsupportedOperationException",
                "java.lang.ArithmeticException",
                "java.lang.NumberFormatException",
                "java.util.NoSuchElementException",
                "java.util.ConcurrentModificationException"
        ));

        // IOException层次
        hierarchy.put("java.io.IOException", List.of(
                "java.io.FileNotFoundException",
                "java.io.EOFException",
                "java.net.SocketException",
                "java.net.ConnectException",
                "java.net.UnknownHostException",
                "java.net.SocketTimeoutException"
        ));

        // SQLException层次
        hierarchy.put("java.sql.SQLException", List.of(
                "java.sql.BatchUpdateException",
                "java.sql.SQLSyntaxErrorException",
                "java.sql.SQLIntegrityConstraintViolationException"
        ));

        // IndexOutOfBoundsException层次
        hierarchy.put("java.lang.IndexOutOfBoundsException", List.of(
                "java.lang.ArrayIndexOutOfBoundsException",
                "java.lang.StringIndexOutOfBoundsException"
        ));

        // ReflectiveOperationException层次
        hierarchy.put("java.lang.ReflectiveOperationException", List.of(
                "java.lang.NoSuchMethodException",
                "java.lang.NoSuchFieldException",
                "java.lang.IllegalAccessException"
        ));
    }

    /**
     * 判断child是否是parent的子类型
     *
     * @param child 子异常类型全限定名
     * @param parent 父异常类型全限定名
     * @return true表示child是parent的子类型
     */
    public boolean isSubtype(String child, String parent) {
        if (child == null || parent == null) {
            return false;
        }

        // 相同类型
        if (child.equals(parent)) {
            return true;
        }

        // 直接子类
        List<String> directSubtypes = hierarchy.get(parent);
        if (directSubtypes != null) {
            if (directSubtypes.contains(child)) {
                return true;
            }
            // 递归检查子类
            for (String subtype : directSubtypes) {
                if (isSubtype(child, subtype)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 添加异常继承关系
     *
     * @param parent 父异常类型全限定名
     * @param child 子异常类型全限定名
     */
    public void addInheritance(String parent, String child) {
        hierarchy.computeIfAbsent(parent, k -> new ArrayList<>());
        List<String> children = hierarchy.get(parent);
        if (!children.contains(child)) {
            children.add(child);
        }
    }

    /**
     * 批量添加异常继承关系
     *
     * @param parent 父异常类型全限定名
     * @param children 子异常类型全限定名列表
     */
    public void addInheritances(String parent, List<String> children) {
        hierarchy.computeIfAbsent(parent, k -> new ArrayList<>());
        List<String> existingChildren = hierarchy.get(parent);
        for (String child : children) {
            if (!existingChildren.contains(child)) {
                existingChildren.add(child);
            }
        }
    }

    /**
     * 获取指定异常类型的所有已知子类型
     *
     * @param parent 父异常类型全限定名
     * @return 所有子类型列表（包括递归子类型）
     */
    public List<String> getAllSubtypes(String parent) {
        List<String> result = new ArrayList<>();
        collectAllSubtypes(parent, result);
        return result;
    }

    /**
     * 递归收集所有子类型
     */
    private void collectAllSubtypes(String parent, List<String> result) {
        List<String> directSubtypes = hierarchy.get(parent);
        if (directSubtypes != null) {
            for (String subtype : directSubtypes) {
                if (!result.contains(subtype)) {
                    result.add(subtype);
                    collectAllSubtypes(subtype, result);
                }
            }
        }
    }

    /**
     * 获取指定异常类型的直接父类型
     *
     * @param child 子异常类型全限定名
     * @return 父类型全限定名，如果没有找到返回null
     */
    public String getParentType(String child) {
        for (Map.Entry<String, List<String>> entry : hierarchy.entrySet()) {
            if (entry.getValue().contains(child)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 检查异常类型是否存在于配置中
     *
     * @param exceptionType 异常类型全限定名
     * @return true表示存在
     */
    public boolean containsException(String exceptionType) {
        // 检查是否作为父类型存在
        if (hierarchy.containsKey(exceptionType)) {
            return true;
        }
        // 检查是否作为子类型存在
        for (List<String> children : hierarchy.values()) {
            if (children.contains(exceptionType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 清除所有配置的继承关系（不包括默认值）
     */
    public void clear() {
        hierarchy.clear();
    }

    /**
     * 重置为默认配置
     */
    public void resetToDefaults() {
        hierarchy.clear();
        initDefaultHierarchy();
    }
}