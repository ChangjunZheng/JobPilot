package com.jobpilot.security;

import com.jobpilot.config.SecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
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
                List.of()));
    }

    @Test
    void issueAndVerifyReturnsUserId() {
        String token = jwtService.issue("user-a");

        assertThat(jwtService.verifyAndGetUserId(token)).isEqualTo("user-a");
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = jwtService.issue("user-a");
        String tampered = token.substring(0, token.length() - 1) + "x";

        assertThatThrownBy(() -> jwtService.verifyAndGetUserId(tampered))
                .isInstanceOf(JwtService.InvalidTokenException.class);
    }

    @Test
    void tokenFromDifferentIssuerIsRejected() {
        JwtService other = new JwtService(new SecurityProperties(
                "a-local-test-secret-that-is-at-least-32-bytes-long",
                "other-app",
                Duration.ofHours(2),
                4,
                List.of()));

        assertThatThrownBy(() -> jwtService.verifyAndGetUserId(other.issue("user-a")))
                .isInstanceOf(JwtService.InvalidTokenException.class);
    }
}
