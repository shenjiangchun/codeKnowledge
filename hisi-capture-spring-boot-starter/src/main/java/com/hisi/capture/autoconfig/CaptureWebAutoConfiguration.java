package com.hisi.capture.autoconfig;

import com.hisi.capture.ingress.http.HttpCaptureFilterJakarta;
import com.hisi.capture.ingress.http.HttpCaptureFilterJavax;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * HTTP 采集过滤器自动配置。
 *
 * 通过嵌套静态 @Configuration 分别处理 SB 2.x (javax.servlet) 与 SB 3.x (jakarta.servlet)。
 * 内层使用 @ConditionalOnClass(name = ...) 字符串形式，避免注解处理阶段类不存在时抛出。
 *
 * 说明：
 * - Javax 分支使用 FilterRegistrationBean 指定 order/URL patterns，
 *   因 pom 编译期依赖 spring-boot-autoconfigure 2.7 (javax.servlet)，
 *   此处 FilterRegistrationBean&lt;T extends javax.servlet.Filter&gt; 与 Javax Filter 兼容。
 * - Jakarta 分支不能引用 javax 版 FilterRegistrationBean（Filter 类型不匹配），
 *   直接暴露 @Bean，由 Spring Boot 3.x 的 ServletContextInitializerBeans 自动注册。
 */
@AutoConfiguration
@ConditionalOnWebApplication
public class CaptureWebAutoConfiguration {

    /**
     * Spring Boot 2.x / javax.servlet 分支。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "javax.servlet.Filter")
    public static class JavaxFilterConfig {

        @Bean
        public HttpCaptureFilterJavax httpCaptureFilterJavax() {
            return new HttpCaptureFilterJavax();
        }

        @Bean
        public FilterRegistrationBean<HttpCaptureFilterJavax> httpCaptureFilterJavaxRegistration(
                HttpCaptureFilterJavax filter) {
            FilterRegistrationBean<HttpCaptureFilterJavax> registration = new FilterRegistrationBean<>();
            registration.setFilter(filter);
            registration.addUrlPatterns("/*");
            registration.setOrder(Integer.MIN_VALUE + 10);
            registration.setName("httpCaptureFilterJavax");
            return registration;
        }
    }

    /**
     * Spring Boot 3.x / jakarta.servlet 分支。
     * 只暴露裸 Filter Bean，由 Spring Boot 3.x 自动注册（默认 URL "/*", order LOWEST_PRECEDENCE）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "jakarta.servlet.Filter")
    public static class JakartaFilterConfig {

        @Bean
        public HttpCaptureFilterJakarta httpCaptureFilterJakarta() {
            return new HttpCaptureFilterJakarta();
        }
    }
}
