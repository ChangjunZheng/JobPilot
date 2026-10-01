package com.jobpilot.controller;

import com.jobpilot.common.ApiResponse;
import com.jobpilot.service.AccountService;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号接口（I-1a 最小闭环）。
 * <p>
 * 注册与登录都在 {@code jobpilot.security.public-paths} 白名单里——它们必须在
 * 「还不知道你是谁」的时候就能访问。登出与刷新令牌属 I-1b。
 */
@RestController
@RequestMapping("/api/v1/auth")
@Validated
public class AuthController {

    private final AccountService accountService;

    public AuthController(AccountService accountService) {
        this.accountService = accountService;
    }

    public record RegisterRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {
    }

    public record TokenResponse(String accessToken) {
    }

    @PostMapping("/register")
    public ApiResponse<TokenResponse> register(@RequestBody @Validated RegisterRequest request) {
        return ApiResponse.ok(new TokenResponse(accountService.register(request.email(), request.password())));
    }

    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@RequestBody @Validated LoginRequest request) {
        return ApiResponse.ok(new TokenResponse(accountService.login(request.email(), request.password())));
    }
}
