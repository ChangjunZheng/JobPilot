package com.jobpilot.application;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 投递记录的 SQL 语义（PRD-FP-7），跑在真实 MySQL 上。
 * <p>
 * 这里验证的是<b>只能靠数据库证明</b>的事：字段合并、清空成 NULL、DATE 闭区间过滤、
 * GROUP BY 统计。用 mock mapper 断言这些只是自证——mock 会照着我写的方式返回。
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional
class ApplicationIntegrationTest extends MySqlIntegrationTestBase {

    private static final String TENANT = "it-app-tenant";

    @Autowired
    private ApplicationService applicationService;
    @Autowired
    private ApplicationMapper applicationMapper;

    @BeforeEach
    void setUp() {
        UserContext.set(TENANT);
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void createsAndReadsBackEveryField() {
        ApplicationEntity created = applicationService.create(command(
                "ACME", "后端工程师", "APPLIED", LocalDate.of(2026, 10, 1)));

        ApplicationEntity loaded = applicationService.get(created.getId());

        assertThat(loaded.getUserId()).isEqualTo(TENANT);
        assertThat(loaded.getCompany()).isEqualTo("ACME");
        assertThat(loaded.getStatus()).isEqualTo("APPLIED");
        assertThat(loaded.getAppliedAt()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void partialUpdateLeavesUnmentionedFieldsAlone() {
        ApplicationEntity created = applicationService.create(new ApplicationService.CreateCommand(
                TENANT, "ACME", "后端工程师", "APPLIED", LocalDate.of(2026, 10, 1),
                "https://jobs.example.com/1", null, "内推"));

        applicationService.update(created.getId(), new ApplicationService.Patch(
                null, null, "INTERVIEWING", null, null, null, null));

        ApplicationEntity reloaded = applicationService.get(created.getId());
        assertThat(reloaded.getStatus()).isEqualTo("INTERVIEWING");
        // 只改状态，其余原样保留
        assertThat(reloaded.getCompany()).isEqualTo("ACME");
        assertThat(reloaded.getSource()).isEqualTo("https://jobs.example.com/1");
        assertThat(reloaded.getNotes()).isEqualTo("内推");
        assertThat(reloaded.getAppliedAt()).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    void emptyStringActuallyClearsTheColumn() {
        ApplicationEntity created = applicationService.create(new ApplicationService.CreateCommand(
                TENANT, "ACME", "后端工程师", "APPLIED", LocalDate.of(2026, 10, 1),
                null, null, "备注原文"));

        applicationService.update(created.getId(), new ApplicationService.Patch(
                null, null, null, null, null, null, ""));

        // 必须真的是 NULL——实体更新默认不写 NULL，这是「误关联的 JD 永远解不掉」的成因
        assertThat(applicationService.get(created.getId()).getNotes()).isNull();
    }

    @Test
    void dateRangeIsInclusiveOnBothEnds() {
        LocalDate day = LocalDate.of(2026, 10, 3);
        applicationService.create(command("同日公司", "后端", "APPLIED", day));
        applicationService.create(command("前一天", "后端", "APPLIED", day.minusDays(1)));

        // 起止同为一天，必须能查到当天那条——DATETIME 配 <= 会漏掉
        List<ApplicationEntity> sameDay = applicationService
                .query(new ApplicationService.Query(null, day, day, 0));

        assertThat(sameDay).extracting(ApplicationEntity::getCompany).containsExactly("同日公司");
    }

    @Test
    void queryFiltersByStatus() {
        applicationService.create(command("A", "后端", "APPLIED", LocalDate.of(2026, 10, 1)));
        applicationService.create(command("B", "后端", "OFFER", LocalDate.of(2026, 10, 2)));

        List<ApplicationEntity> offers = applicationService
                .query(new ApplicationService.Query(ApplicationStatus.OFFER, null, null, 0));

        assertThat(offers).extracting(ApplicationEntity::getCompany).containsExactly("B");
    }

    @Test
    void wishlistRecordsAreExcludedByAnyDateRange() {
        // WISHLIST 的 applied_at 为空，被任何日期条件排除——这是预期行为，写下来免得被当成 bug
        applicationService.create(command("还没投", "后端", "WISHLIST", null));
        applicationService.create(command("已投", "后端", "APPLIED", LocalDate.of(2026, 10, 1)));

        List<ApplicationEntity> ranged = applicationService.query(
                new ApplicationService.Query(null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), 0));

        assertThat(ranged).extracting(ApplicationEntity::getCompany).containsExactly("已投");
    }

    @Test
    void statsCountsByStatusAndZeroFillsTheRest() {
        applicationService.create(command("A", "后端", "APPLIED", LocalDate.of(2026, 10, 1)));
        applicationService.create(command("B", "后端", "APPLIED", LocalDate.of(2026, 10, 2)));
        applicationService.create(command("C", "后端", "OFFER", LocalDate.of(2026, 10, 3)));

        ApplicationService.Stats stats = applicationService.stats(null, null);

        assertThat(stats.total()).isEqualTo(3);
        assertThat(stats.byStatus()).hasSize(7);
        assertThat(stats.byStatus()).containsEntry("APPLIED", 2L).containsEntry("OFFER", 1L);
        assertThat(stats.byStatus()).containsEntry("REJECTED", 0L);
        assertThat(stats.byStatus().values().stream().mapToLong(Long::longValue).sum())
                .isEqualTo(stats.total());
    }

    @Test
    void listIsClampedToTheMaximum() {
        for (int i = 0; i < 5; i++) {
            applicationService.create(command("公司" + i, "后端", "APPLIED", LocalDate.of(2026, 10, 1)));
        }

        List<ApplicationEntity> limited = applicationService
                .query(new ApplicationService.Query(null, null, null, 2));

        // 无分页拦截器，靠 LIMIT 钳制；limit 是解析后的 int，无注入面
        assertThat(limited).hasSize(2);
    }

    @Test
    void deleteRemovesTheRow() {
        ApplicationEntity created = applicationService.create(command(
                "待删", "后端", "APPLIED", LocalDate.of(2026, 10, 1)));

        applicationService.delete(created.getId());

        assertThat(applicationMapper.selectById(created.getId())).isNull();
    }

    private ApplicationService.CreateCommand command(String company, String position,
                                                     String status, LocalDate appliedAt) {
        return new ApplicationService.CreateCommand(
                TENANT, company, position, status, appliedAt, null, null, null);
    }
}
