package com.jobpilot.service;

import com.jobpilot.common.ApiException;
import com.jobpilot.domain.UserAccountEntity;
import com.jobpilot.domain.UserCredentialEntity;
import com.jobpilot.mapper.UserAccountMapper;
import com.jobpilot.mapper.UserCredentialMapper;
import com.jobpilot.security.JwtService;
import com.jobpilot.security.PasswordHasher;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountServiceTest {

    private final UserAccountMapper accountMapper = mock(UserAccountMapper.class);
    private final UserCredentialMapper credentialMapper = mock(UserCredentialMapper.class);
    private final PasswordHasher passwordHasher = mock(PasswordHasher.class);
    private final JwtService jwtService = mock(JwtService.class);

    private final AccountService accountService =
            new AccountService(accountMapper, credentialMapper, passwordHasher, jwtService);

    /**
     * 回归：邮箱不存在时也必须拿 null 哈希走一次校验。
     * <p>
     * 一旦有人把它改回 {@code credential == null || !matches(...)} 的短路写法，
     * 「邮箱未注册」就会比「密码错误」快一整个 bcrypt，统一文案挡不住的时间侧信道又回来了。
     */
    @Test
    void unknownEmailStillRunsThePasswordHash() {
        when(credentialMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> accountService.login("nobody@example.com", "password123"))
                .isInstanceOf(ApiException.class)
                .hasMessage("邮箱或密码不正确");

        verify(passwordHasher).matchesAlwaysHashing("password123", null);
    }

    @Test
    void wrongPasswordDoesNotIssueAToken() {
        when(credentialMapper.selectOne(any())).thenReturn(credential("u1", "$2a$10$stored"));
        when(passwordHasher.matchesAlwaysHashing(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> accountService.login("a@example.com", "wrong"))
                .isInstanceOf(ApiException.class)
                .hasMessage("邮箱或密码不正确");

        verify(jwtService, never()).issue(any());
    }

    @Test
    void successfulLoginIssuesAToken() {
        when(credentialMapper.selectOne(any())).thenReturn(credential("u1", "$2a$10$stored"));
        when(passwordHasher.matchesAlwaysHashing("right", "$2a$10$stored")).thenReturn(true);
        when(accountMapper.selectById("u1")).thenReturn(account("u1", "ACTIVE"));
        when(jwtService.issue("u1")).thenReturn("token");

        assertThat(accountService.login("A@Example.com", "right")).isEqualTo("token");
    }

    @Test
    void disabledAccountCannotLogIn() {
        when(credentialMapper.selectOne(any())).thenReturn(credential("u1", "$2a$10$stored"));
        when(passwordHasher.matchesAlwaysHashing("right", "$2a$10$stored")).thenReturn(true);
        when(accountMapper.selectById("u1")).thenReturn(account("u1", "DISABLED"));

        assertThatThrownBy(() -> accountService.login("a@example.com", "right"))
                .isInstanceOf(ApiException.class)
                .hasMessage("账号不可用");

        verify(jwtService, never()).issue(any());
    }

    private static UserCredentialEntity credential(String userId, String secretHash) {
        UserCredentialEntity credential = new UserCredentialEntity();
        credential.setUserId(userId);
        credential.setSecretHash(secretHash);
        return credential;
    }

    private static UserAccountEntity account(String id, String status) {
        UserAccountEntity account = new UserAccountEntity();
        account.setId(id);
        account.setStatus(status);
        return account;
    }
}
