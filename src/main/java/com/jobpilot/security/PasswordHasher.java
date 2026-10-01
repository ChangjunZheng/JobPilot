package com.jobpilot.security;

import com.jobpilot.config.SecurityProperties;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 密码哈希。只依赖 {@code spring-security-crypto} 这一个工具包，
 * 不引入 {@code spring-boot-starter-security}——后者会自动装配过滤器链把应用锁死。
 * <p>
 * bcrypt 自带随机 salt，同一明文每次哈希结果不同；校验用 {@code matches}，不要自己比较字符串。
 * 强度取自配置（默认 10），调高会线性增加单次登录耗时。
 */
@Component
public class PasswordHasher {

    private final BCryptPasswordEncoder encoder;

    public PasswordHasher(SecurityProperties props) {
        this.encoder = new BCryptPasswordEncoder(props.bcryptStrength());
    }

    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /** 明文与哈希不匹配、或哈希串格式非法，都返回 false，不区分——避免成为探测信号 */
    public boolean matches(String rawPassword, String hashed) {
        if (rawPassword == null || hashed == null) {
            return false;
        }
        try {
            return encoder.matches(rawPassword, hashed);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
