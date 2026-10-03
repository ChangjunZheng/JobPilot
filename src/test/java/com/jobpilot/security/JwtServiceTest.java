package com.jobpilot.security;

import com.jobpilot.config.SecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(new SecurityProperties(
                "a-local-test-secret-that-is-at-least-32-bytes-long",
                "jobpilot",
                Duration.ofHours(2),
                4,
                List.of(),
                null,
                null));
    }

    @Test
    void issueAndVerifyReturnsUserId() {
        String token = jwtService.issue("user-a");

        assertThat(jwtService.verify(token).userId()).isEqualTo("user-a");
    }

    @Test
    void verifyCarriesJtiAndExpiryForRevocation() {
        Instant before = Instant.now();
        JwtService.VerifiedToken verified = jwtService.verify(jwtService.issue("user-a"));

        // jti 是撤销黑名单的键；expiresAt 决定黑名单条目的 TTL，必须与签发 TTL 一致
        assertThat(verified.jti()).isNotBlank();
        assertThat(verified.expiresAt()).isAfter(before.plus(Duration.ofMinutes(119)));
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = jwtService.issue("user-a");
        String[] parts = token.split("\\.");
        // 篡改 payload 而不是签名段末位。签名是对 payload 算的，改它必然对不上。
        //
        // 曾经改的是签名段最后一个字符（替换成 "x"），那是个 6.4% 概率的随机失败：
        // HS256 签名 32 字节经 base64url 编成 43 个字符，末位只贡献 2 个有效位、
        // 低 4 位解码时被丢弃。当末位恰好是 "w"（110000）时换成 "x"（110001）
        // 高 2 位相同，解出的签名完全一样——篡改等于没改，验证照常通过。
        // 实测 2000 次样本复现（末位为 "w" 的 127 次全部被接受）。
        char[] payload = parts[1].toCharArray();
        payload[0] = payload[0] == 'X' ? 'Y' : 'X';
        String tampered = parts[0] + "." + new String(payload) + "." + parts[2];

        assertThat(tampered).as("篡改必须真的改变令牌，否则本用例什么都没验证").isNotEqualTo(token);
        assertThatThrownBy(() -> jwtService.verify(tampered))
                .isInstanceOf(JwtService.InvalidTokenException.class);
    }

    @Test
    void tokenFromDifferentIssuerIsRejected() {
        JwtService other = new JwtService(new SecurityProperties(
                "a-local-test-secret-that-is-at-least-32-bytes-long",
                "other-app",
                Duration.ofHours(2),
                4,
                List.of(),
                null,
                null));

        assertThatThrownBy(() -> jwtService.verify(other.issue("user-a")))
                .isInstanceOf(JwtService.InvalidTokenException.class);
    }
}
