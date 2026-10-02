package com.jobpilot.security;

import com.jobpilot.config.SecurityProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * HS256 签名令牌的签发与校验。
 * <p>
 * 自研而不是引入 spring-boot-starter-security：本项目只需要「签一个令牌、验一个令牌」，
 * 不需要整套过滤器链。而手写 HS256 又要自己处理恒定时间比较、exp/nbf 校验、
 * alg 混淆攻击（把 RS256 换成 HS256 用公钥当密钥）——所以用 JJWT，不自造密码学。
 * <p>
 * <b>单一密钥风险与对策</b>：HS256 的签名与验签共用一把密钥，任何持有它的人都能伪造令牌。
 * 因此密钥只从环境变量注入；将来若拆多服务，应换成 RS256（验签方只持有公钥）。
 */
@Component
public class JwtService {

    private final SecretKey key;
    private final String issuer;
    private final Duration ttl;

    public JwtService(SecurityProperties props) {
        // WeakKeyException 在密钥短于 256 位时抛出，属于启动即失败，正是我们想要的
        this.key = Keys.hmacShaKeyFor(props.jwtSecret().getBytes(StandardCharsets.UTF_8));
        this.issuer = props.jwtIssuer();
        this.ttl = props.accessTokenTtl();
    }

    /** 签发：subject 放用户 ID（即租户键），jti 供撤销黑名单（I-1b）使用 */
    public String issue(String userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(userId)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** 校验通过后的令牌载荷：userId 是租户键，jti 供黑名单比对，expiresAt 供撤销条目定 TTL */
    public record VerifiedToken(String userId, String jti, Instant expiresAt) {
    }

    /**
     * 校验并取出令牌载荷。
     * <p>
     * 任何失败（过期 / 签名不符 / 结构损坏 / 签发方不符）统一抛 {@link InvalidTokenException}，
     * 不向调用方区分原因——区分等于给攻击者一个探测器。
     * {@code verifyWith(key)} 会把算法限定为与密钥匹配的 HMAC，从而拒绝 {@code alg=none}
     * 与签名算法混淆。
     */
    public VerifiedToken verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String userId = claims.getSubject();
            if (userId == null || userId.isBlank()) {
                throw new InvalidTokenException("令牌缺少 subject");
            }
            String jti = claims.getId();
            if (jti == null || jti.isBlank()) {
                throw new InvalidTokenException("令牌缺少 jti");
            }
            return new VerifiedToken(userId, jti, claims.getExpiration().toInstant());
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException("令牌无效");
        }
    }

    /** 令牌本身无效（过期/伪造/损坏）。刻意不继承 ApiException：调用方应按 401 处理 */
    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message) {
            super(message);
        }
    }
}
