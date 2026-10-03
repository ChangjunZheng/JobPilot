package com.jobpilot.controller;

import com.jobpilot.application.ApplicationService;
import com.jobpilot.common.ApiResponse;
import com.jobpilot.domain.ApplicationEntity;
import com.jobpilot.domain.ApplicationStatus;
import com.jobpilot.security.UserContext;
import jakarta.validation.constraints.NotBlank;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 投递记录 API（PRD-FP-7）。
 * <p>
 * <b>请求体一律不含 {@code userId}</b>：身份只从 JWT 解析、经 {@code UserContext} 传递，
 * 客户端即使在 body 里塞一个也不会被绑定——record 里根本没这个字段。
 */
@RestController
@RequestMapping("/api/v1/applications")
@Validated
public class ApplicationController {

    private final ApplicationService applicationService;

    public ApplicationController(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    public record CreateRequest(
            @NotBlank String company,
            @NotBlank String position,
            @NotBlank String status,
            LocalDate appliedAt,
            String source,
            String jdDocumentId,
            String notes
    ) {
    }

    /** 部分更新：字段缺省 = 不修改；空串 = 清空（`appliedAt` 为日期，只能设不能清） */
    public record PatchRequest(
            String company,
            String position,
            String status,
            LocalDate appliedAt,
            String source,
            String jdDocumentId,
            String notes
    ) {
    }

    public record ApplicationResponse(
            String id,
            String company,
            String position,
            String status,
            LocalDate appliedAt,
            String source,
            String jdDocumentId,
            String notes,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {

        static ApplicationResponse from(ApplicationEntity entity) {
            return new ApplicationResponse(entity.getId(), entity.getCompany(), entity.getPosition(),
                    entity.getStatus(), entity.getAppliedAt(), entity.getSource(),
                    entity.getJdDocumentId(), entity.getNotes(),
                    entity.getCreatedAt(), entity.getUpdatedAt());
        }
    }

    public record StatsResponse(long total, Map<String, Long> byStatus) {

        static StatsResponse from(ApplicationService.Stats stats) {
            return new StatsResponse(stats.total(), stats.byStatus());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ApplicationResponse> create(@RequestBody @Validated CreateRequest request) {
        ApplicationEntity created = applicationService.create(new ApplicationService.CreateCommand(
                UserContext.require(), request.company(), request.position(), request.status(),
                request.appliedAt(), request.source(), request.jdDocumentId(), request.notes()));
        return ApiResponse.ok(ApplicationResponse.from(created));
    }

    @GetMapping("/{id}")
    public ApiResponse<ApplicationResponse> get(@PathVariable String id) {
        return ApiResponse.ok(ApplicationResponse.from(applicationService.get(id)));
    }

    /** 列表：按状态与投递日期范围过滤；`status` 传未知值时显式报错，不返回空列表 */
    @GetMapping
    public ApiResponse<List<ApplicationResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "0") int limit) {
        ApplicationStatus parsed = status == null || status.isBlank()
                ? null
                : ApplicationStatus.parse(status);
        List<ApplicationResponse> items = applicationService
                .query(new ApplicationService.Query(parsed, from, to, limit))
                .stream().map(ApplicationResponse::from).toList();
        return ApiResponse.ok(items);
    }

    @PatchMapping("/{id}")
    public ApiResponse<ApplicationResponse> update(@PathVariable String id,
                                                   @RequestBody @Validated PatchRequest request) {
        ApplicationEntity updated = applicationService.update(id, new ApplicationService.Patch(
                request.company(), request.position(), request.status(), request.appliedAt(),
                request.source(), request.jdDocumentId(), request.notes()));
        return ApiResponse.ok(ApplicationResponse.from(updated));
    }

    /**
     * 删除记录。
     * <p>
     * 与「撤回投递」（状态置 {@code WITHDRAWN}）是两件事：那个是业务状态，这个是移除记录
     * （例如建错了）。<b>刻意不提供对应的 agent 工具</b>——PRD-FP-2.2 的工具清单里没有删除，
     * 删除不该由模型发起。
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        applicationService.delete(id);
        return ApiResponse.ok(null);
    }

    /** 总数与各状态数量；七种状态全部零填充 */
    @GetMapping("/stats")
    public ApiResponse<StatsResponse> stats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(StatsResponse.from(applicationService.stats(from, to)));
    }
}
