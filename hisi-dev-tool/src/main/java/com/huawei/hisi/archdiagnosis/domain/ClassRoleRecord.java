package com.huawei.hisi.archdiagnosis.domain;

/**
 * 类名 + 分层角色（诊断侧纯读 {@code ClassNode.classRole} 的最小输入）。
 *
 * @param className 全限定类名
 * @param classRole CONTROLLER / SERVICE / REPOSITORY / MODEL / UTILITY / UNKNOWN
 */
public record ClassRoleRecord(String className, String classRole) {
}
