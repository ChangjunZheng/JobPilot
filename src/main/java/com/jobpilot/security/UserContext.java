package com.jobpilot.security;

import com.jobpilot.common.UnauthorizedException;

/**
 * 当前请求的租户标识（ThreadLocal）。
 * <p>
 * 契约：**只在认证拦截器一处写入**，且必须在请求结束时清除。
 * 漏清除会污染线程池里复用该线程的下一个请求——这是跨租户泄漏最隐蔽的一种成因。
 * <p>
 * 已知局限：ThreadLocal 不跨 {@code @Async}、自定义线程池与 {@code CompletableFuture}。
 * 任何脱离请求线程的执行上下文（如 I-1c 计划的索引 worker）必须**显式传递**租户标识，
 * 不得依赖这里静默穿透。
 */
public final class UserContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(String userId) {
        CURRENT.set(userId);
    }

    /** 无上下文时返回 null。仅供测试与需要区分「缺失」与「非法」的场景使用。 */
    public static String get() {
        return CURRENT.get();
    }

    /**
     * 取当前租户，缺失即抛 {@link UnauthorizedException}。
     * <p>
     * 刻意不返回 null：调用方拿到 null 后若顺手拼进 SQL 或过滤条件，就是一次「无过滤」查询。
     * 早失败远胜晚失败。
     */
    public static String require() {
        String userId = CURRENT.get();
        if (userId == null || userId.isBlank()) {
            throw new UnauthorizedException("未认证或缺少租户上下文");
        }
        return userId;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
