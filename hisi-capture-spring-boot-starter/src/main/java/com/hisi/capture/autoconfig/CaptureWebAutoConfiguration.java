package com.hisi.capture.autoconfig;

import com.hisi.capture.ingress.http.HttpCaptureFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnWebApplication
public class CaptureWebAutoConfiguration {

    @Bean
    public HttpCaptureFilter httpCaptureFilter() {
        return new HttpCaptureFilter();
    }

    @Bean
    public FilterRegistrationBean<HttpCaptureFilter> httpCaptureFilterRegistration(HttpCaptureFilter filter) {
        FilterRegistrationBean<HttpCaptureFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.addUrlPatterns("/*");
        registration.setOrder(Integer.MIN_VALUE + 10); // 高优先级
        registration.setName("httpCaptureFilter");
        return registration;
    }
}
