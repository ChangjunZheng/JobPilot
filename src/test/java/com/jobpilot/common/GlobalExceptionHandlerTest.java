package com.jobpilot.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(response.getBody().error().message()).isEqualTo("不支持的请求方法");
    }
}
