package com.jobpilot.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 投递记录（PRD-FP-7）。
 * <p>
 * {@code status} 存 {@link ApplicationStatus} 的名称字符串——与既有实体保持一致的写法，
 * 校验与枚举由 {@link ApplicationStatus#parse} 在边界完成。
 */
@TableName("job_application")
public class ApplicationEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    /** 租户键。写入时必须由服务层显式设置——拦截器<b>不会</b>覆盖实体已带的 user_id */
    private String userId;
    private String company;
    private String position;
    /** ApplicationStatus 名称 */
    private String status;
    /** 投递日期；WISHLIST 阶段可为空 */
    private LocalDate appliedAt;
    private String source;
    /** 关联的 JD 文档 ID（kb_document.id），可选 */
    private String jdDocumentId;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getCompany() {
        return company;
    }

    public void setCompany(String company) {
        this.company = company;
    }

    public String getPosition() {
        return position;
    }

    public void setPosition(String position) {
        this.position = position;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDate getAppliedAt() {
        return appliedAt;
    }

    public void setAppliedAt(LocalDate appliedAt) {
        this.appliedAt = appliedAt;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getJdDocumentId() {
        return jdDocumentId;
    }

    public void setJdDocumentId(String jdDocumentId) {
        this.jdDocumentId = jdDocumentId;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
