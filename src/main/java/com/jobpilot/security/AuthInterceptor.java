package com.jobpilot.security;

import com.jobpilot.common.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器：解析 {@code Authorization: Bearer <token>} → 写入 {@link UserContext}。
 * <p>
 * <b>为什么用 HandlerInterceptor 而不是 Filter：</b>
 * Filter 跑在 DispatcherServlet 之前，它抛出的异常**不会**进入 {@code @RestControllerAdvice}
 * （那是 DispatcherServlet 的处理器异常链），会直接冒到容器，响应体形状与 {@code ApiResponse} 不一致。
 * 用拦截器则异常自然被全局异常处理器接住，错误信封只有一处定义。
 * <p>
 * <b>顺序契约（关键）</b>：校验全部做完、确认要放行之后，才在最后一步 {@code set}。
 * 拦截器在 {@code preHandle} 抛异常时 {@code afterCompletion} 不会被调用——
 * 若先 set 再抛，ThreadLocal 就泄漏到线程池的下一个请求上了。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    /** 登出接口据此撤销当前令牌；由 {@link #preHandle} 在认证通过后写入 request 属性 */
    public static final String ATTR_JTI = AuthInterceptor.class.getName() + ".jti";
    public static final String ATTR_EXPIRES_AT = AuthInterceptor.class.getName() + ".expiresAt";

    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklist;

    public AuthInterceptor(JwtService jwtService, TokenBlacklistService tokenBlacklist) {
        this.jwtService = jwtService;
        this.tokenBlacklist = tokenBlacklist;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new UnauthorizedException("缺少访问令牌");
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        JwtService.VerifiedToken verified;
        try {
            verified = jwtService.verify(token);
        } catch (JwtService.InvalidTokenException e) {
            throw new UnauthorizedException("令牌无效或已过期");
        }
        if (tokenBlacklist.isRevoked(verified.jti())) {
            throw new UnauthorizedException("令牌已注销");
        }
        // 所有可能抛异常的步骤都已走完，到这里才写上下文
        UserContext.set(verified.userId());
        request.setAttribute(ATTR_JTI, verified.jti());
        request.setAttribute(ATTR_EXPIRES_AT, verified.expiresAt());
        return true;
    }

    /**
     * 请求结束必清。不清会让线程池复用的下一个请求带着上一个租户的标识——
     * 这类污染极难复现，靠的就是这里和一条回归测试。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }
}
