package com.jobpilot.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 账号主体；id 同时是租户键，业务表的 user_id 指向它 */
@TableName("user_account")
public class UserAccountEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    /** ACTIVE / DISABLED */
    private String status;
    /** 注册时同意的隐私政策版本（合规留痕，V4） */
    private String privacyVersion;
    private LocalDateTime privacyConsentedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPrivacyVersion() {
        return privacyVersion;
    }

    public void setPrivacyVersion(String privacyVersion) {
        this.privacyVersion = privacyVersion;
    }

    public LocalDateTime getPrivacyConsentedAt() {
        return privacyConsentedAt;
    }

    public void setPrivacyConsentedAt(LocalDateTime privacyConsentedAt) {
        this.privacyConsentedAt = privacyConsentedAt;
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
