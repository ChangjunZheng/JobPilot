package com.jobpilot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;

/**
 * Ollama / Chroma 的 HTTP 客户端工厂。
 * 连接 3 秒即断（快速触发降级）；读超时 120 秒容忍本地模型冷加载首包慢的问题。
 */
@Configuration(proxyBeanMethods = false) // 还是单例，但不代理方法调用
public class HttpClientConfig {

    @Bean
    public ClientHttpRequestFactory ragRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(120).toMillis());
        return factory;
    }
}
