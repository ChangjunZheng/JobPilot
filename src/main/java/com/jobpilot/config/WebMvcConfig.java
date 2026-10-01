package com.jobpilot.config;

import com.jobpilot.security.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册认证拦截器并声明放行路径。
 * <p>
 * 用 {@code WebMvcConfigurer} 的路径模式而不是在拦截器内部手写字符串判断：
 * 放行规则集中一处、可配置，且 {@code excludePathPatterns} 是 Spring MVC 既有语义，
 * 不需要自己处理通配符与上下文路径。
 */
@Configuration(proxyBeanMethods = false)
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final SecurityProperties securityProperties;

    public WebMvcConfig(AuthInterceptor authInterceptor, SecurityProperties securityProperties) {
        this.authInterceptor = authInterceptor;
        this.securityProperties = securityProperties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                // /error 也要放行：它由容器在异常后转发，此时没有业务身份可言；
                // 不放行会让错误响应本身再触发一次 401，掩盖真正的失败原因。
                .excludePathPatterns(securityProperties.publicPaths())
                .excludePathPatterns("/error");
    }
}
