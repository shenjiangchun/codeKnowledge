package com.huawei.hisi.config;

/**
 * 请求级身份信息，由 JwtAuthFilter 设置到 request attribute，
 * 由 AdminOnlyInterceptor 读取进行权限判断。
 */
public record SecurityContext(String username, String role) {
    public static final String ATTR_NAME = "SECURITY_CONTEXT";

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}
