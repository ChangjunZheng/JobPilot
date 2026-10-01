package com.jobpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * 认证配置（jobpilot.security.*）。
 * <p>
 * 默认值写在 application.yml 的 {@code ${ENV:default}} 占位符里，此处不写默认——
 * 与 {@link RagProperties} 保持一致：绑定类只是解析结果的载体。
 */
@ConfigurationProperties(prefix = "jobpilot.security")
public record SecurityProperties(
        /** HS256 签名密钥。<b>刻意不给默认值</b>——落在 git 里的默认密钥等于公开签名权 */
        String jwtSecret,
        String jwtIssuer,
        Duration accessTokenTtl,
        int bcryptStrength,
        /** 无需认证的路径，Ant 风格 */
        List<String> publicPaths
) {

    /** 密钥缺失或过短（HS256 要求 ≥ 256 位）时给出可操作的错误，而不是等到签发时才炸 */
    public SecurityProperties {
        if (jwtSecret == null || jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "jobpilot.security.jwt-secret 必须配置且不少于 32 字节（HS256 要求 256 位密钥）。"
                            + "本地置于 application-local.yml（已 gitignore），CI 通过环境变量注入。");
        }
    }
}
