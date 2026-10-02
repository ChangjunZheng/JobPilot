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
        String tampered = token.substring(0, token.length() - 1) + "x";

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
