package com.jobpilot.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登出撤销的端到端回归（I-1b）：登出 → 原令牌立即 401。
 * <p>
 * 黑名单存 Redis，本地以 Windows 服务常驻；CI 没有 Redis——此时撤销检查走 fail-open
 * （见 {@link TokenBlacklistService}），「登出即失效」无法成立，本类整体跳过而不是假绿。
 * 拦截器层的拒绝逻辑（不依赖真实 Redis）由 {@code AuthInterceptorTest.revokedTokenIsRejectedBeforeContextIsSet}
 * 用 mock 覆盖，CI 仍然有兜底验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class LogoutIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void requireRedis() {
        assumeTrue(redisReachable(), "Redis 不可用（如 CI 环境）：跳过登出端到端用例，拦截器层已有 mock 兜底");
    }

    @Test
    void logoutInvalidatesTokenImmediately() throws Exception {
        String token = jwtService.issue("logout-test-tenant");

        // 登出前：令牌有效（文档不存在是 404，不是 401——证明认证已通过）
        mockMvc.perform(get("/api/v1/knowledge/documents/no-such-doc")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // 登出后：同一令牌立即 401，且错误码是「已注销」语义
        mockMvc.perform(get("/api/v1/knowledge/documents/no-such-doc")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void registerRequiresExplicitPrivacyConsent() throws Exception {
        String body = """
                {"email":"logout-test@example.com","password":"password123","privacyConsent":false}
                """;
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PRIVACY_CONSENT_REQUIRED"));
    }

    @Test
    @Transactional
    void registerWithConsentIssuesUsableToken() throws Exception {
        String body = """
                {"email":"logout-test@example.com","password":"password123","privacyConsent":true}
                """;
        String accessToken = com.jayway.jsonpath.JsonPath.read(
                mockMvc.perform(post("/api/v1/auth/register")
                                .contentType("application/json")
                                .content(body))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                "$.data.accessToken");

        // 新令牌走一遍业务接口：认证通过（404 = 资源不存在，而非 401）
        mockMvc.perform(get("/api/v1/knowledge/documents/no-such-doc")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void privacyNoticeIsPublicAndCarriesVersion() throws Exception {
        mockMvc.perform(get("/api/v1/auth/privacy-notice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").isNotEmpty())
                .andExpect(jsonPath("$.data.text").isNotEmpty());
    }

    private boolean redisReachable() {
        try {
            redis.opsForValue().set("auth:test:ping", "1", Duration.ofSeconds(10));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
