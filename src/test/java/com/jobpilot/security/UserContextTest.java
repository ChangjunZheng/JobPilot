package com.jobpilot.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserContextTest {

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void requireReturnsCurrentTenant() {
        UserContext.set("user-a");

        assertThat(UserContext.require()).isEqualTo("user-a");
    }

    @Test
    void requireFailsWhenContextMissing() {
        assertThatThrownBy(UserContext::require)
                .isInstanceOf(com.jobpilot.common.UnauthorizedException.class);
    }

    @Test
    void clearRemovesTenantFromReusedThread() {
        UserContext.set("user-a");
        UserContext.clear();

        assertThat(UserContext.get()).isNull();
    }
}
