package com.jobpilot.knowledge;

import com.jobpilot.config.IngestProperties;
import com.jobpilot.domain.KbDocumentEntity;
import com.jobpilot.mapper.KbDocumentMapper;
import com.jobpilot.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

/**
 * DB 队列 worker（I-1c，ARCHITECTURE.md §4.1）：
 * 轮询认领 {@code PENDING} 任务 → 交 {@link DocumentIngestService#process} 索引。
 * <p>
 * <b>为什么是自建调度循环而不是 {@code @Async} 或消息队列</b>：@Async 的任务在内存里，
 * 重启即丢在途工作；MQ 对单集群单体是过量设计。行即消息（PENDING/PROCESSING 状态机），
 * {@code FOR UPDATE SKIP LOCKED} 保证多线程/多实例认领互斥，重启后任务原地仍在。
 * <p>
 * <b>租户上下文是显式传递的</b>：认领阶段为跨租户扫描（mapper 上 {@code @InterceptorIgnore}），
 * 认领成功后 worker 把行自带的 {@code user_id} 写回 {@code UserContext} 再执行索引，
 * 处理全程受租户拦截器保护；结束时清除，不向线程池的下一个任务泄漏。
 */
@Component
public class IngestWorker implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestWorker.class);

    /** 单次认领的候选批量；逐个过租户上限闸门，取第一个可执行的 */
    private static final int CLAIM_BATCH = 8;

    private final KbDocumentMapper documentMapper;
    private final DocumentIngestService ingestService;
    private final IngestProperties props;
    private final TransactionTemplate txTemplate;
    private ScheduledExecutorService scheduler;

    public IngestWorker(KbDocumentMapper documentMapper,
                        DocumentIngestService ingestService,
                        IngestProperties props,
                        PlatformTransactionManager transactionManager) {
        this.documentMapper = documentMapper;
        this.ingestService = ingestService;
        this.props = props;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.enabled()) {
            log.info("导入 worker 未启用（jobpilot.ingest.enabled=false），任务将停留 PENDING");
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int stale = documentMapper.resetStaleProcessing(now, now.minus(props.staleProcessingTimeout()));
        if (stale > 0) {
            // 上个 JVM 生命周期遗留的 PROCESSING 行：不接管则永久滞留中间态
            log.warn("启动接管：{} 个超过 {} 的僵死 PROCESSING 任务已重置为 PENDING",
                    stale, props.staleProcessingTimeout());
        }
        CustomizableThreadFactory threadFactory = new CustomizableThreadFactory("ingest-worker-");
        threadFactory.setDaemon(true);
        scheduler = Executors.newScheduledThreadPool(props.workerThreads(), threadFactory);
        long intervalMs = props.pollInterval().toMillis();
        for (int i = 0; i < props.workerThreads(); i++) {
            scheduler.scheduleWithFixedDelay(this::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        }
        log.info("导入 worker 已启动：threads={} pollInterval={}ms maxPerTenant={} maxRetries={}",
                props.workerThreads(), intervalMs, props.maxPerTenant(), props.maxRetries());
    }

    @PreDestroy
    void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** 单次心跳：认领一个任务并处理；任何异常只记日志，不中断调度 */
    void tick() {
        try {
            KbDocumentEntity task = claim();
            if (task == null) {
                return;
            }
            log.info("认领导入任务 documentId={} user={} retryCount={}",
                    task.getId(), task.getUserId(), task.getRetryCount());
            UserContext.set(task.getUserId());
            try {
                ingestService.process(task);
            } finally {
                UserContext.clear();
            }
        } catch (Exception e) {
            log.error("导入 tick 失败（不影响下一轮）", e);
        }
    }

    /**
     * 认领一个任务（事务内）：候选按先来先服务，逐个过「每租户在途上限」闸门，
     * 用 CAS（PENDING → PROCESSING）落定归属；没抢到或超限的候选随事务提交解锁，保持 PENDING。
     */
    KbDocumentEntity claim() {
        return txTemplate.execute(status -> {
            List<KbDocumentEntity> candidates =
                    documentMapper.selectClaimCandidates(LocalDateTime.now(), CLAIM_BATCH);
            for (KbDocumentEntity candidate : candidates) {
                if (documentMapper.countProcessingByTenant(candidate.getUserId()) >= props.maxPerTenant()) {
                    continue;
                }
                if (documentMapper.markProcessing(candidate.getId()) == 1) {
                    // CAS 已落库；内存实体同步翻转——process() 以 status==PROCESSING 为前置校验，
                    // 不同步的话 worker 每个任务都会在这里误判终止
                    candidate.setStatus("PROCESSING");
                    return candidate;
                }
            }
            return null;
        });
    }
}
