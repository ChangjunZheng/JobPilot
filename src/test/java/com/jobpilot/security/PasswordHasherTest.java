package com.jobpilot.security;

import com.jobpilot.config.SecurityProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher(properties());

    @Test
    void samePasswordCanBeVerified() {
        String hash = hasher.hash("password123");

        assertThat(hash).isNotEqualTo("password123");
        assertThat(hasher.matchesAlwaysHashing("password123", hash)).isTrue();
        assertThat(hasher.matchesAlwaysHashing("wrong-password", hash)).isFalse();
    }

    /**
     * 哈希缺失（账号不存在）时必须返回 false。至于「仍然跑了 bcrypt」这一时序性质，
     * 单测里做时间断言只会变成 flaky，靠 {@code AccountServiceTest} 验证调用参数来兜。
     */
    @Test
    void missingHashNeverMatches() {
        assertThat(hasher.matchesAlwaysHashing("password123", null)).isFalse();
    }

    @Test
    void hashingSamePasswordTwiceProducesDifferentHashes() {
        assertThat(hasher.hash("password123"))
                .isNotEqualTo(hasher.hash("password123"));
    }

    private static SecurityProperties properties() {
        return new SecurityProperties(
                "a-local-test-secret-that-is-at-least-32-bytes-long",
                "jobpilot",
                Duration.ofHours(2),
                4,
                List.of());
    }
}
