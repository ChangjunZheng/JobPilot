package com.jobpilot.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * 已签发令牌的撤销黑名单（Redis，I-1b）。
 * <p>
 * key 为 {@code auth:jwt:deny:<jti>}，TTL 设为令牌剩余有效期：过期令牌本就过不了验签，
 * 黑名单条目不需要活得比令牌久，Redis 不会因此积累垃圾。
 * <p>
 * <b>降级语义（刻意 fail-open）</b>：Redis 不可用时，撤销检查按「未撤销」处理、撤销写入记 ERROR 后放行。
 * 理由：黑名单只存 Redis，没有「回源到库」的替代——若 fail-closed，Redis 一抖动整个 API 拒绝所有人，
 * 可用性代价远大于撤销缺口；而令牌本身是短期的，缺口上界就是令牌剩余寿命（access TTL，默认 2h）。
 * 将来若要求强撤销保证，可引入「每用户 token 版本号」落 MySQL，把检查升级为 fail-closed。
 */
@Service
public class TokenBlacklistService {

    private static final Logger log = LoggerFactory.getLogger(TokenBlacklistService.class);

    static final String KEY_PREFIX = "auth:jwt:deny:";

    private final StringRedisTemplate redis;

    public TokenBlacklistService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 撤销一个令牌；存储不可用时记 ERROR 并放行（撤销是尽力而为，取舍见类注释） */
    public void revoke(String jti, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            return; // 已过期令牌过不了验签，无需撤销
        }
        try {
            redis.opsForValue().set(KEY_PREFIX + jti, "1", ttl);
        } catch (RuntimeException e) {
            log.error("SECURITY 令牌撤销写入失败，本次登出未能在服务端生效 jti={}", jti, e);
        }
    }

    /** 黑名单命中返回 true；存储不可用时按未撤销处理（fail-open，取舍见类注释） */
    public boolean isRevoked(String jti) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + jti));
        } catch (RuntimeException e) {
            log.warn("SECURITY 撤销黑名单不可用，按未撤销处理（fail-open）");
            return false;
        }
    }
}
