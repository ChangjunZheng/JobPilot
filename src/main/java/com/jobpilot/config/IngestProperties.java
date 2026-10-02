package com.jobpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 导入异步化配置（jobpilot.ingest.*，I-1c）。
 * <p>
 * 默认值全部落在 application.yml；record 只保留规范构造器——
 * 加第二个构造器会让 Boot 的绑定直接报「No default constructor found」（实测踩过，见 2026-10-02 交接）。
 */
@ConfigurationProperties(prefix = "jobpilot.ingest")
public record IngestProperties(
        /** worker 总开关；测试与单机排错时可关掉，让任务停留 PENDING 由人观察 */
        boolean enabled,
        /** 没有可认领任务时的轮询间隔 */
        Duration pollInterval,
        /** 认领并处理任务的线程数；SKIP LOCKED 保证多线程/多实例不重复认领 */
        int workerThreads,
        /** 重排队次数上限（首次执行不计）；超过即 FAILED，不再重试 */
        int maxRetries,
        /** 首次重试退避基数，之后按 2^n 指数增长 */
        Duration retryBackoff,
        /** 单租户同时处于 PROCESSING 的任务上限，防止批量导入饿死其他租户 */
        int maxPerTenant,
        /** PROCESSING 超过此时长视为僵死（JVM 重启遗留），启动时重置回 PENDING */
        Duration staleProcessingTimeout
) {
}
