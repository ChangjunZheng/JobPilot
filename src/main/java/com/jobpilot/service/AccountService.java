package com.jobpilot.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.jobpilot.common.ApiException;
import com.jobpilot.common.ErrorCode;
import com.jobpilot.domain.UserAccountEntity;
import com.jobpilot.domain.UserCredentialEntity;
import com.jobpilot.mapper.UserAccountMapper;
import com.jobpilot.mapper.UserCredentialMapper;
import com.jobpilot.security.JwtService;
import com.jobpilot.security.PasswordHasher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号注册与登录。
 * <p>
 * 这两张表在租户拦截器里是 {@code ignoreTable} 的——认证流程必须在「知道你是谁」之前
 * 按邮箱反查账号，天然是跨租户的。代价是这里的查询不受拦截器保护，
 * 因此**所有查询都必须显式带上自己的过滤条件**，不能依赖自动注入。
 */
@Service
public class AccountService {

    private static final String PROVIDER_EMAIL = "EMAIL";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserAccountMapper accountMapper;
    private final UserCredentialMapper credentialMapper;
    private final PasswordHasher passwordHasher;
    private final JwtService jwtService;

    public AccountService(UserAccountMapper accountMapper,
                          UserCredentialMapper credentialMapper,
                          PasswordHasher passwordHasher,
                          JwtService jwtService) {
        this.accountMapper = accountMapper;
        this.credentialMapper = credentialMapper;
        this.passwordHasher = passwordHasher;
        this.jwtService = jwtService;
    }

    /** 注册：创建账号 + 邮箱凭证，返回可立即使用的访问令牌 */
    @Transactional
    public String register(String email, String rawPassword) {
        String identifier = normalizeEmail(email);
        validatePassword(rawPassword);

        UserAccountEntity account = new UserAccountEntity();
        account.setStatus(STATUS_ACTIVE);
        accountMapper.insert(account);

        UserCredentialEntity credential = new UserCredentialEntity();
        credential.setUserId(account.getId());
        credential.setProvider(PROVIDER_EMAIL);
        credential.setIdentifier(identifier);
        credential.setSecretHash(passwordHasher.hash(rawPassword));
        try {
            credentialMapper.insert(credential);
        } catch (DuplicateKeyException e) {
            // 唯一键 (provider, identifier) 兜住并发注册；事务回滚，账号不会留下
            throw new ApiException(ErrorCode.EMAIL_TAKEN, "该邮箱已注册");
        }
        return jwtService.issue(account.getId());
    }

    /** 登录：按邮箱反查凭证并校验密码，返回访问令牌 */
    public String login(String email, String rawPassword) {
        String identifier = normalizeEmail(email);
        UserCredentialEntity credential = credentialMapper.selectOne(new QueryWrapper<UserCredentialEntity>()
                .eq("provider", PROVIDER_EMAIL)
                .eq("identifier", identifier));

        // 账号不存在与密码错误返回同一句话，避免把「这个邮箱注册过没有」变成可探测的信号。
        // 这里刻意不写成 credential == null || !matches(...)：短路会让账号不存在时跳过 bcrypt，
        // 耗时差异把这层伪装拆穿。改成无条件校验一次，哈希缺失时由 PasswordHasher 用哑哈希顶上。
        String secretHash = credential == null ? null : credential.getSecretHash();
        if (!passwordHasher.matchesAlwaysHashing(rawPassword, secretHash)) {
            throw new ApiException(ErrorCode.BAD_CREDENTIALS, "邮箱或密码不正确");
        }
        // 走到这里 credential 必非空：secretHash 为 null 时上面必返回 false 并已抛出

        UserAccountEntity account = accountMapper.selectById(credential.getUserId());
        if (account == null || !STATUS_ACTIVE.equals(account.getStatus())) {
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED, "账号不可用");
        }
        return jwtService.issue(account.getId());
    }

    /** 邮箱大小写不敏感：唯一键建在原始字符串上，规范化必须在写入与查询两侧保持一致 */
    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "邮箱不能为空");
        }
        return email.trim().toLowerCase();
    }

    private void validatePassword(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "密码至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
    }
}
