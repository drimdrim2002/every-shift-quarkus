package org.acme.model;

import com.google.cloud.firestore.annotation.Exclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Cloud Firestore에 저장되는 Job Execution 문서 모델
 */
@RegisterForReflection
public class JobExecution {

    private String id;                    // Document ID (UUID)
    private String tenantId;
    private String organizationName;
    private ExecutionStatus status;
    private String resultJson;            // 직렬화된 결과
    private String errorMessage;
    private Integer hardScore;
    private Integer softScore;            // 레거시 단일 soft 점수 (읽기 호환)
    private Integer night48RestSoftScore;
    private Integer night32RestSoftScore;
    private Integer undesiredSoftScore;
    private Integer fairSoftScore;
    private Integer desiredSoftScore;
    private Long createdAt;               // Timestamp (epoch millis)
    private Long startedAt;
    private Long completedAt;
    private String requestJson;           // 원본 요청 (디버깅용)

    // Firestore에서 document ID를 필드로 매핑하기 위해 사용
    @Exclude
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public JobExecution() {
        // No-arg constructor required for Firestore deserialization
    }

    // Getters and Setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getOrganizationName() {
        return organizationName;
    }

    public void setOrganizationName(String organizationName) {
        this.organizationName = organizationName;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public void setStatus(ExecutionStatus status) {
        this.status = status;
    }

    public String getResultJson() {
        return resultJson;
    }

    public void setResultJson(String resultJson) {
        this.resultJson = resultJson;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Integer getHardScore() {
        return hardScore;
    }

    public void setHardScore(Integer hardScore) {
        this.hardScore = hardScore;
    }

    public Integer getSoftScore() {
        return softScore;
    }

    public void setSoftScore(Integer softScore) {
        this.softScore = softScore;
    }

    public Integer getNight48RestSoftScore() {
        return night48RestSoftScore;
    }

    public void setNight48RestSoftScore(Integer night48RestSoftScore) {
        this.night48RestSoftScore = night48RestSoftScore;
    }

    public Integer getNight32RestSoftScore() {
        return night32RestSoftScore;
    }

    public void setNight32RestSoftScore(Integer night32RestSoftScore) {
        this.night32RestSoftScore = night32RestSoftScore;
    }

    public Integer getUndesiredSoftScore() {
        return undesiredSoftScore;
    }

    public void setUndesiredSoftScore(Integer undesiredSoftScore) {
        this.undesiredSoftScore = undesiredSoftScore;
    }

    public Integer getFairSoftScore() {
        return fairSoftScore;
    }

    public void setFairSoftScore(Integer fairSoftScore) {
        this.fairSoftScore = fairSoftScore;
    }

    public Integer getDesiredSoftScore() {
        return desiredSoftScore;
    }

    public void setDesiredSoftScore(Integer desiredSoftScore) {
        this.desiredSoftScore = desiredSoftScore;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Long startedAt) {
        this.startedAt = startedAt;
    }

    public Long getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Long completedAt) {
        this.completedAt = completedAt;
    }

    public String getRequestJson() {
        return requestJson;
    }

    public void setRequestJson(String requestJson) {
        this.requestJson = requestJson;
    }

    // Helper methods

    @Exclude
    public LocalDateTime getCreatedAtAsDateTime() {
        return epochToLocalDateTime(createdAt);
    }

    @Exclude
    public LocalDateTime getStartedAtAsDateTime() {
        return epochToLocalDateTime(startedAt);
    }

    @Exclude
    public LocalDateTime getCompletedAtAsDateTime() {
        return epochToLocalDateTime(completedAt);
    }

    @Exclude
    public static LocalDateTime epochToLocalDateTime(Long epochMillis) {
        if (epochMillis == null) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }

    // Builder pattern for convenient creation

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final JobExecution instance = new JobExecution();

        public Builder id(String id) {
            instance.id = id;
            return this;
        }

        public Builder tenantId(String tenantId) {
            instance.tenantId = tenantId;
            return this;
        }

        public Builder organizationName(String organizationName) {
            instance.organizationName = organizationName;
            return this;
        }

        public Builder status(ExecutionStatus status) {
            instance.status = status;
            return this;
        }

        public Builder createdAt(Long createdAt) {
            instance.createdAt = createdAt;
            return this;
        }

        public Builder requestJson(String requestJson) {
            instance.requestJson = requestJson;
            return this;
        }

        public JobExecution build() {
            return instance;
        }
    }
}
