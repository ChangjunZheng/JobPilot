package com.jobpilot.controller;

import com.jobpilot.common.ApiException;
import com.jobpilot.common.ApiResponse;
import com.jobpilot.common.ErrorCode;
import com.jobpilot.common.UnauthorizedException;
import com.jobpilot.config.SecurityProperties;
import com.jobpilot.security.AuthInterceptor;
import com.jobpilot.security.TokenBlacklistService;
import com.jobpilot.service.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 账号接口（I-1a 注册登录 + I-1b 登出撤销）。
 * <p>
 * register / login / privacy-notice 在 {@code jobpilot.security.public-paths} 白名单里——
 * 它们必须在「还不知道你是谁」的时候就能访问。<b>logout 刻意不在白名单</b>：
 * 登出必须携带有效令牌，服务端才知道要撤销哪一个会话。
 */
@RestController
@RequestMapping("/api/v1/auth")
@Validated
public class AuthController {

    private final AccountService accountService;
    private final TokenBlacklistService tokenBlacklist;
    private final SecurityProperties securityProperties;

    public AuthController(AccountService accountService,
                          TokenBlacklistService tokenBlacklist,
                          SecurityProperties securityProperties) {
        this.accountService = accountService;
        this.tokenBlacklist = tokenBlacklist;
        this.securityProperties = securityProperties;
    }

    public record RegisterRequest(
            @NotBlank @Email String email,
            @NotBlank String password,
            /** 注册即视为对当期隐私政策的同意，必须显式为 true */
            @NotNull Boolean privacyConsent
    ) {
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {
    }

    public record TokenResponse(String accessToken) {
    }

    public record PrivacyNotice(String version, String text) {
    }

    /** 当前隐私政策与数据用途说明；客户端应在注册前展示并让用户确认 */
    @GetMapping("/privacy-notice")
    public ApiResponse<PrivacyNotice> privacyNotice() {
        return ApiResponse.ok(new PrivacyNotice(
                securityProperties.privacyNoticeVersion(), securityProperties.privacyNotice()));
    }

    /** 注册：创建账号 + 邮箱凭证，返回可立即使用的访问令牌 */
    @PostMapping("/register")
    public ApiResponse<TokenResponse> register(@RequestBody @Validated RegisterRequest request) {
        if (!Boolean.TRUE.equals(request.privacyConsent())) {
            throw new ApiException(ErrorCode.PRIVACY_CONSENT_REQUIRED, "注册需先同意隐私政策与数据用途说明");
        }
        return ApiResponse.ok(new TokenResponse(
                accountService.register(request.email(), request.password(),
                        securityProperties.privacyNoticeVersion())));
    }

    /** 登录：按邮箱反查凭证并校验密码，返回访问令牌 */
    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@RequestBody @Validated LoginRequest request) {
        return ApiResponse.ok(new TokenResponse(accountService.login(request.email(), request.password())));
    }

    /**
     * 登出：把当前令牌的 jti 写入 Redis 黑名单，原凭证立即失效。
     * <p>
     * {@code jti} 由认证拦截器写入 request 属性——logout 不在 public-paths 里，走到这里必已认证；
     * 属性缺失说明放行配置被改错（如把 logout 加进了白名单），按 401 暴露而不是静默空撤销。
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        String jti = (String) request.getAttribute(AuthInterceptor.ATTR_JTI);
        Instant expiresAt = (Instant) request.getAttribute(AuthInterceptor.ATTR_EXPIRES_AT);
        if (jti == null || expiresAt == null) {
            throw new UnauthorizedException("缺少可注销的会话");
        }
        tokenBlacklist.revoke(jti, expiresAt);
        return ApiResponse.ok(null);
    }
}
