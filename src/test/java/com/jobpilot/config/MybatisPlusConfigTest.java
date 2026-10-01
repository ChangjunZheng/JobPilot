package com.jobpilot.config;

import com.jobpilot.common.UnauthorizedException;
import com.jobpilot.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MybatisPlusConfigTest {

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void tenantHandlerUsesCurrentContext() {
        UserContext.set("tenant-a");
        var interceptor = new MybatisPlusConfig().mybatisPlusInterceptor();
        var tenant = (com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor)
                interceptor.getInterceptors().get(0);

        assertThat(tenant.getTenantLineHandler().getTenantIdColumn()).isEqualTo("user_id");
        assertThat(tenant.getTenantLineHandler().getTenantId().toString()).contains("tenant-a");
        assertThat(tenant.getTenantLineHandler().ignoreTable("user_account")).isTrue();
        assertThat(tenant.getTenantLineHandler().ignoreTable("kb_document")).isFalse();
    }

    @Test
    void tenantHandlerFailsWithoutContext() {
        var interceptor = new MybatisPlusConfig().mybatisPlusInterceptor();
        var tenant = (com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor)
                interceptor.getInterceptors().get(0);

        assertThatThrownBy(() -> tenant.getTenantLineHandler().getTenantId())
                .isInstanceOf(UnauthorizedException.class);
    }
}
