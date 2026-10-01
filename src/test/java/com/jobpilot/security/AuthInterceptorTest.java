package com.jobpilot.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthInterceptorTest {

    private final JwtService jwtService = new JwtService(new com.jobpilot.config.SecurityProperties(
            "a-local-test-secret-that-is-at-least-32-bytes-long",
            "jobpilot", java.time.Duration.ofHours(2), 4, java.util.List.of()));
    private final AuthInterceptor interceptor = new AuthInterceptor(jwtService);

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
