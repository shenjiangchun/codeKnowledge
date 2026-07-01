package com.hisi.capture.autoconfig;

import com.hisi.capture.ingress.http.HttpCaptureFilter;
import com.hisi.capture.util.SizeLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.servlet.Filter;

/**
 * HTTP 采集过滤器自动配置。
 *
 * 使用 @ConditionalOnClass 确保只在 javax.servlet 可用时注册（SB 2.x）。
 * SB 3.x 需要额外 jakarta 版本配置（见 jakarta source set 或独立模块）。
 */
@Configuration
@ConditionalOnWebApplication
@ConditionalOnClass(Filter.class)
public class CaptureWebAutoConfiguration {

    @Bean
    public HttpCaptureFilter httpCaptureFilter() {
        return new HttpCaptureFilter();
    }

    @Bean
    public FilterRegistrationBean<HttpCaptureFilter> httpCaptureFilterRegistration(
            HttpCaptureFilter filter) {
        FilterRegistrationBean<HttpCaptureFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.addUrlPatterns("/*");
        registration.setOrder(Integer.MIN_VALUE + 10);
        registration.setName("httpCaptureFilter");
        return registration;
    }
}
