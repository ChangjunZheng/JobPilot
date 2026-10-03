package com.jobpilot.application;

import com.jobpilot.common.ApiException;
import com.jobpilot.domain.ApplicationEntity;
import com.jobpilot.domain.ApplicationStatus;
import com.jobpilot.mapper.ApplicationMapper;
import com.jobpilot.security.UserContext;
import com.jobpilot.support.MySqlIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 投递记录的<b>跨租户隔离</b>——本项目的架构不变量。
 * <p>
 * 数据用 {@link JdbcTemplate} 播种：<b>故意绕过租户拦截器</b>，这样两个租户的行都真实存在，
 * 才能证明「读不到 / 改不动」是拦截器的功劳，而不是「库里本来就没有」。
 * <p>
 * 这里补上 ROADMAP §4.1「六个业务对象 × 读/改/删/检索」中的投递对象——
 * 此前只有文档覆盖，而「改」和「删」这两个动词从未被任何业务对象验证过。
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional
class ApplicationTenantIsolationIntegrationTest extends MySqlIntegrationTestBase {

    @Autowired
    private ApplicationService applicationService;
    @Autowired
    private ApplicationMapper applicationMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String tenantA;
    private String tenantB;
    private String appOfA;
    private String appOfB;

    @BeforeEach
    void setUp() {
        tenantA = "iso-a-" + UUID.randomUUID();
        tenantB = "iso-b-" + UUID.randomUUID();
        appOfA = UUID.randomUUID().toString();
        appOfB = UUID.randomUUID().toString();
        seed(appOfA, tenantA, "A 的公司");
        seed(appOfB, tenantB, "B 的公司");
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    // ── 读 ──────────────────────────────────────────────────────

    @Test
    void tenantCannotReadAnotherTenantsRecord() {
        UserContext.set(tenantA);

        assertThat(applicationService.get(appOfA).getCompany()).isEqualTo("A 的公司");
        // 跨租户与不存在对外不可区分——这是有意的，避免把「别人有没有这条」变成可探测信号
        assertThatThrownBy(() -> applicationService.get(appOfB))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("投递记录不存在");
    }

    @Test
    void queryReturnsOnlyTheCurrentTenantsRecords() {
        UserContext.set(tenantA);

        List<ApplicationEntity> items = applicationService
                .query(new ApplicationService.Query(null, null, null, 0));

        assertThat(items).extracting(ApplicationEntity::getId).containsExactly(appOfA);
    }

    // ── 改 ──────────────────────────────────────────────────────

    @Test
    void tenantCannotUpdateAnotherTenantsRecord() {
        UserContext.set(tenantA);

        // 服务层先做租户范围内的 selectById，跨租户直接查不到 → NOT_FOUND
        assertThatThrownBy(() -> applicationService.update(appOfB, new ApplicationService.Patch(
                "被篡改", null, "OFFER", LocalDate.of(2026, 10, 1), null, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("投递记录不存在");

        // B 的行一个字段都没被动过——切到 B 的视角复核
        UserContext.set(tenantB);
        assertThat(applicationService.get(appOfB).getCompany()).isEqualTo("B 的公司");
        assertThat(applicationService.get(appOfB).getStatus()).isEqualTo("APPLIED");
    }

    @Test
    void updateAffectsZeroRowsWhenTheRowIsOutOfTenantScope() {
        UserContext.set(tenantA);

        // 绕过服务层的先读，直接打 mapper：证明「影响 0 行」是数据库层的事实，
        // 而不是服务层提前拦截的结果
        int affected = applicationMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ApplicationEntity>()
                        .eq("id", appOfB)
                        .set("company", "被篡改"));

        assertThat(affected).isZero();
        UserContext.set(tenantB);
        assertThat(applicationService.get(appOfB).getCompany()).isEqualTo("B 的公司");
    }

    // ── 删 ──────────────────────────────────────────────────────

    @Test
    void tenantCannotDeleteAnotherTenantsRecord() {
        UserContext.set(tenantA);

        assertThatThrownBy(() -> applicationService.delete(appOfB))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("投递记录不存在");

        // B 的行还在
        UserContext.set(tenantB);
        assertThat(applicationService.get(appOfB)).isNotNull();
    }

    @Test
    void deleteAffectsZeroRowsWhenTheRowIsOutOfTenantScope() {
        UserContext.set(tenantA);

        int affected = applicationMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ApplicationEntity>()
                        .eq("id", appOfB));

        assertThat(affected).isZero();
        UserContext.set(tenantB);
        assertThat(applicationService.get(appOfB)).isNotNull();
    }

    // ── 检索（统计） ────────────────────────────────────────────

    @Test
    void statsCountOnlyTheCurrentTenantsRecords() {
        UserContext.set(tenantA);

        ApplicationService.Stats stats = applicationService.stats(null, null);

        // 库里 A、B 各一条；A 只能看到自己那条
        assertThat(stats.total()).isEqualTo(1);
        assertThat(stats.byStatus()).containsEntry("APPLIED", 1L);
    }

    @Test
    void statusFilterCannotLeakAcrossTenants() {
        // 给 B 造一条 A 没有的状态，若过滤失效就会漏出来
        jdbcTemplate.update("UPDATE job_application SET status = 'OFFER' WHERE id = ?", appOfB);
        UserContext.set(tenantA);

        List<ApplicationEntity> offers = applicationService
                .query(new ApplicationService.Query(ApplicationStatus.OFFER, null, null, 0));

        assertThat(offers).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────

    /** 故意走原生 JDBC：绕过租户拦截器，让两个租户的行都真实落库 */
    private void seed(String id, String userId, String company) {
        jdbcTemplate.update("INSERT INTO job_application "
                        + "(id, user_id, company, position, status, applied_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, '后端工程师', 'APPLIED', '2026-10-01', NOW(3), NOW(3))",
                id, userId, company);
    }
}
