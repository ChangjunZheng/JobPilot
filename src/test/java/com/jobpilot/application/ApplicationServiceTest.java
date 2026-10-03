package com.jobpilot.application;

import com.jobpilot.common.ApiException;
import com.jobpilot.domain.ApplicationEntity;
import com.jobpilot.mapper.ApplicationMapper;
import com.jobpilot.mapper.KbDocumentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 投递记录服务的校验与失败路径（PRD-FP-7）。
 * <p>
 * mock mapper、不依赖 MySQL。<b>字段合并与清空的语义交给
 * {@code ApplicationIntegrationTest}</b> 用真实 SQL 断言——那是「某个列真的变成了 NULL」这类
 * 只能靠数据库证明的事，用 mock wrapper 断言只是自证。
 */
class ApplicationServiceTest {

    private ApplicationMapper applicationMapper;
    private KbDocumentMapper documentMapper;
    private ApplicationService service;

    private static final String TENANT = "tenant-a";

    @BeforeEach
    void setUp() {
        applicationMapper = mock(ApplicationMapper.class);
        documentMapper = mock(KbDocumentMapper.class);
        service = new ApplicationService(applicationMapper, documentMapper);
    }

    // ── 创建校验 ───────────────────────────────────────────────

    @Test
    void unknownStatusIsRejectedExplicitly() {
        assertThatThrownBy(() -> service.create(command("NOT_A_STATUS", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知投递状态")
                .hasMessageContaining("WISHLIST");

        verify(applicationMapper, never()).insert(any(ApplicationEntity.class));
    }

    @Test
    void nonWishlistStatusRequiresAppliedDate() {
        // 「已投递但没有投递日期」语义不自洽
        assertThatThrownBy(() -> service.create(command("APPLIED", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须提供投递日期");

        verify(applicationMapper, never()).insert(any(ApplicationEntity.class));
    }

    @Test
    void wishlistWithoutAppliedDateIsAllowed() {
        service.create(command("WISHLIST", null));

        // 想投还没投，没有投递日期是正常的
        verify(applicationMapper).insert(any(ApplicationEntity.class));
    }

    @Test
    void overlengthNotesIsRejectedRatherThanTruncated() {
        String tooLong = "x".repeat(2001);

        assertThatThrownBy(() -> service.create(new ApplicationService.CreateCommand(
                TENANT, "ACME", "后端", "WISHLIST", null, null, null, tooLong)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("备注超过长度上限");

        // 静默截断用户内容比报错更糟
        verify(applicationMapper, never()).insert(any(ApplicationEntity.class));
    }

    @Test
    void unknownJdDocumentIsRejected() {
        when(documentMapper.selectById("nope")).thenReturn(null);

        assertThatThrownBy(() -> service.create(new ApplicationService.CreateCommand(
                TENANT, "ACME", "后端", "WISHLIST", null, null, "nope", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JD 文档不存在");
    }

    @Test
    void createSetsTenantFromCommand() {
        service.create(command("WISHLIST", null));

        ArgumentCaptor<ApplicationEntity> captor = ArgumentCaptor.forClass(ApplicationEntity.class);
        verify(applicationMapper).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(TENANT);
    }

    // ── 更新：影响行数是关键 ────────────────────────────────────

    @Test
    void updateOnMissingRowIsNotFound() {
        when(applicationMapper.selectById("gone")).thenReturn(null);

        assertThatThrownBy(() -> service.update("gone", patchStatus("OFFER")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("投递记录不存在");
    }

    @Test
    void updateThatAffectsNoRowIsNotFoundNotSuccess() {
        when(applicationMapper.selectById("raced")).thenReturn(existing("raced"));
        // 行读得到，但更新影响 0 行（并发删除，或拦截器把它过滤掉了）
        when(applicationMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.update("raced", new ApplicationService.Patch(
                null, null, "OFFER", LocalDate.of(2026, 10, 1), null, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("投递记录不存在");
    }

    @Test
    void updateRejectsStatusDateMismatchOnMergedState() {
        // 库里只有 WISHLIST（无日期），补丁把它推成 INTERVIEWING 却不给日期——
        // 校验看的是合并后的完整状态，不是只看补丁里出现过的字段
        ApplicationEntity current = existing("id-1");
        current.setStatus("WISHLIST");
        current.setAppliedAt(null);
        when(applicationMapper.selectById("id-1")).thenReturn(current);

        assertThatThrownBy(() -> service.update("id-1", patchStatus("INTERVIEWING")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须提供投递日期");

        verify(applicationMapper, never()).update(any(ApplicationEntity.class), any());
    }

    // ── 删除 ──────────────────────────────────────────────────

    @Test
    void deleteThatAffectsNoRowIsNotFound() {
        when(applicationMapper.delete(any())).thenReturn(0);

        assertThatThrownBy(() -> service.delete("ghost"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("投递记录不存在");
    }

    // ── 统计 ──────────────────────────────────────────────────

    @Test
    void statsZeroFillsAllStatusesAndTotalEqualsBucketSum() {
        when(applicationMapper.selectMaps(any())).thenReturn(List.of(
                Map.of("status", "APPLIED", "cnt", 3L),
                Map.of("status", "OFFER", "cnt", 1L)));

        ApplicationService.Stats stats = service.stats(null, null);

        assertThat(stats.total()).isEqualTo(4);
        // 七种状态全部出现，未命中的为 0——模型不必自己推断哪些是 0
        assertThat(stats.byStatus()).hasSize(7);
        assertThat(stats.byStatus()).containsEntry("APPLIED", 3L).containsEntry("OFFER", 1L);
        assertThat(stats.byStatus()).containsEntry("REJECTED", 0L);
        assertThat(stats.byStatus().values().stream().mapToLong(Long::longValue).sum())
                .isEqualTo(stats.total());
    }

    @Test
    void statsBucketsUnrecognisedValueInsteadOfDroppingIt() {
        // 正常不可达（写入已校验），但一旦出现，丢弃会让「各桶之和 ≠ 总数」而无人察觉
        when(applicationMapper.selectMaps(any())).thenReturn(List.of(
                Map.of("status", "APPLIED", "cnt", 2L),
                Map.of("status", "LEGACY_VALUE", "cnt", 1L)));

        ApplicationService.Stats stats = service.stats(null, null);

        assertThat(stats.total()).isEqualTo(3);
        assertThat(stats.byStatus()).containsEntry("UNKNOWN", 1L);
        assertThat(stats.byStatus().values().stream().mapToLong(Long::longValue).sum())
                .isEqualTo(stats.total());
    }

    // ── helpers ───────────────────────────────────────────────

    private ApplicationService.CreateCommand command(String status, LocalDate appliedAt) {
        return new ApplicationService.CreateCommand(
                TENANT, "ACME", "后端工程师", status, appliedAt, null, null, null);
    }

    private ApplicationService.Patch patchStatus(String status) {
        return new ApplicationService.Patch(null, null, status, null, null, null, null);
    }

    private ApplicationEntity existing(String id) {
        ApplicationEntity entity = new ApplicationEntity();
        entity.setId(id);
        entity.setUserId(TENANT);
        entity.setCompany("ACME");
        entity.setPosition("后端工程师");
        entity.setStatus("WISHLIST");
        return entity;
    }
}
