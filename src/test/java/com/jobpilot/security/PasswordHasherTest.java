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
        assertThat(hasher.matches("password123", hash)).isTrue();
        assertThat(hasher.matches("wrong-password", hash)).isFalse();
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
