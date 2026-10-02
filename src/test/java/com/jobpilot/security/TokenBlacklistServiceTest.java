package com.jobpilot.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TokenBlacklistServiceTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final TokenBlacklistService service = new TokenBlacklistService(redis);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void wireValueOps() {
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void revokeWritesDenyKeyWithTokenRemainingTtl() {
        Instant expiresAt = Instant.now().plus(Duration.ofHours(1));

        service.revoke("jti-1", expiresAt);

        // TTL 必须约等于令牌剩余寿命：写得比令牌久是垃圾数据，写得比令牌短会让撤销提前失效
        var captor = org.mockito.ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(org.mockito.ArgumentMatchers.eq(TokenBlacklistService.KEY_PREFIX + "jti-1"),
                org.mockito.ArgumentMatchers.eq("1"), captor.capture());
        assertThat(captor.getValue())
                .isBetween(Duration.ofMinutes(59), Duration.ofHours(1));
    }

    @Test
    void revokeSkipsAlreadyExpiredToken() {
        service.revoke("jti-expired", Instant.now().minusSeconds(10));

        verifyNoInteractions(valueOps);
    }

    @Test
    void revokeDegradesToNoOpWhenRedisUnavailable() {
        doThrow(new RedisConnectionFailureException("down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        // fail-open：撤销存储挂掉不能把登出接口打挂（取舍见 TokenBlacklistService 类注释）
        assertThatCode(() -> service.revoke("jti-1", Instant.now().plus(Duration.ofHours(1))))
                .doesNotThrowAnyException();
    }

    @Test
    void isRevokedReflectsDenyKey() {
        when(redis.hasKey(TokenBlacklistService.KEY_PREFIX + "hit")).thenReturn(true);
        when(redis.hasKey(TokenBlacklistService.KEY_PREFIX + "miss")).thenReturn(false);

        assertThat(service.isRevoked("hit")).isTrue();
        assertThat(service.isRevoked("miss")).isFalse();
    }

    @Test
    void isRevokedFailsOpenWhenRedisUnavailable() {
        when(redis.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(service.isRevoked("jti-1")).isFalse();
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }
}
