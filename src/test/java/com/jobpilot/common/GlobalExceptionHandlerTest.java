package com.jobpilot.common;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unexpectedExceptionUsesSafeMessage() {
        ApiResponse<Void> response = handler.handleUnexpected(new IllegalStateException("database password"));

        assertThat(response.error().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.error().message()).isEqualTo("服务暂时不可用，请稍后重试");
        assertThat(response.error().message()).doesNotContain("IllegalStateException", "database password");
    }

    @Test
    void unsupportedMethodUses405AndSafeEnvelope() {
        var response = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("TRACE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody().error().code()).isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void apiExceptionEnumMapsToHttpStatusAndKeepsWireContract() {
        // 线上 error.code 即枚举 name()，与裸字符串时代的取值逐字一致；拼写漂移在编译期被拒绝
        HttpServletRequest request = mock(HttpServletRequest.class);

        var notFound = handler.handleApiException(new ApiException(ErrorCode.NOT_FOUND, "文档不存在"), request);
        var taken = handler.handleApiException(new ApiException(ErrorCode.EMAIL_TAKEN, "该邮箱已注册"), request);

        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notFound.getBody().error().code()).isEqualTo("NOT_FOUND");
        assertThat(taken.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(taken.getBody().error().code()).isEqualTo("EMAIL_TAKEN");
    }
}
