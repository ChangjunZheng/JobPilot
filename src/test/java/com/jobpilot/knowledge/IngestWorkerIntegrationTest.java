package com.jobpilot.knowledge;

import com.jobpilot.config.IngestProperties;
import com.jobpilot.domain.KbDocumentEntity;
import com.jobpilot.mapper.KbDocumentMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 认领 SQL 的真实 MySQL 回归（I-1c）：到期过滤、先来先服务、每租户在途上限、僵死接管。
 * <p>
 * worker 通过 {@code jobpilot.ingest.enabled=false} 关闭，避免后台线程与本测试竞争同一批种子行；
 * SKIP LOCKED 的互斥语义本身需要并发观测，单线程回归只验证 SQL 行为正确，多实例互斥靠语句构造保证。
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = "jobpilot.ingest.enabled=false")
@Transactional
class IngestWorkerIntegrationTest {

    @Autowired
    private IngestWorker worker;
    @Autowired
    private KbDocumentMapper documentMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private IngestProperties ingestProps;

    private String tenantA;
    private String tenantB;

    @BeforeEach
    void setUp() {
        tenantA = "tenant-a-" + UUID.randomUUID();
        tenantB = "tenant-b-" + UUID.randomUUID();
    }

    @AfterEach
    void clearContext() {
        com.jobpilot.security.UserContext.clear();
    }

    @Test
    void claimPicksOldestDueTaskAndFlipsItToProcessing() {
        String older = seedDocument(tenantA, "PENDING", 0, null, minutesAgo(10));
        seedDocument(tenantA, "PENDING", 0, null, minutesAgo(5));

        KbDocumentEntity claimed = worker.claim();

        assertThat(claimed).isNotNull();
        assertThat(claimed.getId()).isEqualTo(older); // 先来先服务
        assertThat(claimed.getStatus()).isEqualTo("PROCESSING");
        // 落库状态已翻转（认领事务提交在 test 事务内，同连接可见）；用 JdbcTemplate 绕开租户拦截器
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM kb_document WHERE id = ?", String.class, older))
                .isEqualTo("PROCESSING");
    }

    @Test
    void claimSkipsTasksWhoseRetryBackoffHasNotElapsed() {
        seedDocument(tenantA, "PENDING", 1, LocalDateTime.now().plusMinutes(10), minutesAgo(1));

        assertThat(worker.claim()).isNull();
    }

    @Test
    void claimHonorsPerTenantProcessingCap() {
        seedDocument(tenantA, "PROCESSING", 0, null, minutesAgo(12));
        seedDocument(tenantA, "PROCESSING", 0, null, minutesAgo(11)); // 租户 A 在途 = 上限（默认 2）
        seedDocument(tenantA, "PENDING", 0, null, minutesAgo(9));     // 候选更早，但 A 已满员
        String tenantBTask = seedDocument(tenantB, "PENDING", 0, null, minutesAgo(8));

        KbDocumentEntity claimed = worker.claim();

        // 候选按 created_at 排序先看到 A 的任务，但 A 在途满员被跳过；不能让 A 饿死 B
        assertThat(claimed).isNotNull();
        assertThat(claimed.getId()).isEqualTo(tenantBTask);
    }

    @Test
    void staleProcessingRowsAreTakenOverAtStartup() {
        String stale = seedDocument(tenantA, "PROCESSING", 1, null, minutesAgo(30)); // 超过 staleProcessingTimeout
        seedDocument(tenantA, "PROCESSING", 0, null, minutesAgo(1)); // 新鲜任务不动

        LocalDateTime now = LocalDateTime.now();
        int reset = documentMapper.resetStaleProcessing(now, now.minus(ingestProps.staleProcessingTimeout()));

        assertThat(reset).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM kb_document WHERE id = ?", String.class, stale))
                .isEqualTo("PENDING");
    }

    private String seedDocument(String userId, String status, int retryCount,
                                LocalDateTime nextRetryAt, LocalDateTime updatedAt) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO kb_document "
                        + "(id, user_id, name, doc_type, content, status, index_version, chunk_count, "
                        + " retry_count, next_retry_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'PLAIN_TEXT', '种子原文', ?, 1, 0, ?, ?, ?, ?)",
                id, userId, "种子-" + id.substring(0, 8), status, retryCount,
                nextRetryAt == null ? null : Timestamp.valueOf(nextRetryAt),
                Timestamp.valueOf(updatedAt.minusMinutes(1)), Timestamp.valueOf(updatedAt));
        return id;
    }

    private LocalDateTime minutesAgo(int minutes) {
        return LocalDateTime.now().minusMinutes(minutes);
    }
}
