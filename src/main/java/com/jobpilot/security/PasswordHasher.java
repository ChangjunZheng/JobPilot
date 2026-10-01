package com.jobpilot.security;

import com.jobpilot.config.SecurityProperties;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 密码哈希。只依赖 {@code spring-security-crypto} 这一个工具包，
 * 不引入 {@code spring-boot-starter-security}——后者会自动装配过滤器链把应用锁死。
 * <p>
 * bcrypt 自带随机 salt，同一明文每次哈希结果不同；校验一律走 {@link #matchesAlwaysHashing}，
 * 不要自己比较字符串。强度取自配置（默认 10），调高会线性增加单次登录耗时。
 */
@Component
public class PasswordHasher {

    private final BCryptPasswordEncoder encoder;

    /**
     * 与配置强度一致的哑哈希，构造时算一次。账号不存在时用它顶替真实哈希，
     * 让「邮箱未注册」与「密码错误」付出等量的 bcrypt 开销。
     * 必须用同一个 encoder 生成——写死一个强度 10 的常量在配置改成别的强度后就不等量了。
     */
    private final String decoyHash;

    public PasswordHasher(SecurityProperties props) {
        this.encoder = new BCryptPasswordEncoder(props.bcryptStrength());
        this.decoyHash = encoder.encode(UUID.randomUUID().toString());
    }

    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /**
     * 校验密码，且**无论账号是否存在都完整跑一次 bcrypt**。
     * <p>
     * 这是防用户枚举的另一半：统一错误文案堵住的是响应**内容**，堵不住响应**耗时**。
     * 若账号不存在就跳过 bcrypt（例如调用方写成 {@code credential == null || !matches(...)}），
     * 空账号会比密码错误快一整个 bcrypt，攻击者采样几百次就能把「这个邮箱注册过没有」测出来。
     * <p>
     * 因此调用方**必须**把查询结果原样传进来（可能为 null），不要自己先判空短路。
     *
     * @param hashed 库里的哈希；为 null 表示账号不存在
     * @return 密码是否匹配。{@code hashed} 为 null 时必为 false
     */
    public boolean matchesAlwaysHashing(String rawPassword, String hashed) {
        String target = hashed != null ? hashed : decoyHash;
        boolean matched = verify(rawPassword, target);
        return hashed != null && matched;
    }

    /** 明文与哈希不匹配、或哈希串格式非法，都返回 false，不区分——避免成为探测信号 */
    private boolean verify(String rawPassword, String hashed) {
        if (rawPassword == null) {
            return false;
        }
        try {
            return encoder.matches(rawPassword, hashed);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
