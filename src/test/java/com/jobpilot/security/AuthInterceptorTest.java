package com.jobpilot.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthInterceptorTest {

    private static final String SECRET = "a-local-test-secret-that-is-at-least-32-bytes-long";

    private final JwtService jwtService = new JwtService(new com.jobpilot.config.SecurityProperties(
            SECRET, "jobpilot", java.time.Duration.ofHours(2), 4, java.util.List.of(), null, null));
    private final TokenBlacklistService tokenBlacklist = mock(TokenBlacklistService.class);
    private final AuthInterceptor interceptor = new AuthInterceptor(jwtService, tokenBlacklist);

    @BeforeEach
    void blacklistNeverHitsByDefault() {
        when(tokenBlacklist.isRevoked(anyString())).thenReturn(false);
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void validTokenSetsContextAndCompletionClearsIt() throws Exception {
        String token = jwtService.issue("tenant-a");
        HttpServletRequest request = requestWith("Bearer " + token);

        assertThat(interceptor.preHandle(request, mock(HttpServletResponse.class), new Object())).isTrue();
        assertThat(UserContext.require()).isEqualTo("tenant-a");

        interceptor.afterCompletion(request, mock(HttpServletResponse.class), new Object(), null);
        assertThat(UserContext.get()).isNull();
    }

    @Test
    void preHandleExposesJtiAndExpiryForLogout() throws Exception {
        // 登出接口从 request 属性取撤销目标；属性缺失会让 logout 静默失效
        String token = jwtService.issue("tenant-a");
        HttpServletRequest request = requestWith("Bearer " + token);
        JwtService.VerifiedToken verified = jwtService.verify(token);

        interceptor.preHandle(request, mock(HttpServletResponse.class), new Object());

        verify(request).setAttribute(eq(AuthInterceptor.ATTR_JTI), eq(verified.jti()));
        verify(request).setAttribute(eq(AuthInterceptor.ATTR_EXPIRES_AT), eq(verified.expiresAt()));
    }

    @Test
    void revokedTokenIsRejectedBeforeContextIsSet() {
        // 黑名单命中必须在 UserContext.set 之前拒绝——顺序契约（见 AuthInterceptor 类注释）不可破坏
        String token = jwtService.issue("tenant-a");
        when(tokenBlacklist.isRevoked(anyString())).thenReturn(true);

        assertThatThrownBy(() -> interceptor.preHandle(
                        requestWith("Bearer " + token), mock(HttpServletResponse.class), new Object()))
                .isInstanceOf(com.jobpilot.common.UnauthorizedException.class);
        assertThat(UserContext.get()).isNull();
    }

    @Test
    void missingTokenDoesNotLeaveContext() {
        HttpServletRequest request = requestWith(null);

        assertThatThrownBy(() -> interceptor.preHandle(request, mock(HttpServletResponse.class), new Object()))
                .isInstanceOf(com.jobpilot.common.UnauthorizedException.class);
        assertThat(UserContext.get()).isNull();
    }

    private HttpServletRequest requestWith(String authorization) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn(authorization);
        return request;
    }
}
