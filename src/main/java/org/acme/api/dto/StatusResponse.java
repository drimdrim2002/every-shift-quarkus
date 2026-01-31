package org.acme.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.acme.model.ExecutionStatus;
import org.acme.model.JobExecution;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * GET /api/status/{id} 응답 DTO
 */
@RegisterForReflection
public record StatusResponse(
        @JsonProperty("execution_id") String executionId,
        @JsonProperty("tenant_id") String tenantId,
        @JsonProperty("organization_name") String organizationName,
        @JsonProperty("status") String status,
        @JsonProperty("score") ScoreInfo score,
        @JsonProperty("result") Map<String, Object> result,
        @JsonProperty("error_message") String errorMessage,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("started_at") LocalDateTime startedAt,
        @JsonProperty("completed_at") LocalDateTime completedAt
) {
    public record ScoreInfo(
            @JsonProperty("hard_score") int hardScore,
            @JsonProperty("soft_score") int softScore
    ) {}

    /**
     * JobExecution 엔티티로부터 StatusResponse 생성
     */
    public static StatusResponse from(JobExecution job) {
        ScoreInfo scoreInfo = null;
        if (job.getHardScore() != null && job.getSoftScore() != null) {
            scoreInfo = new ScoreInfo(job.getHardScore(), job.getSoftScore());
        }

        return new StatusResponse(
                job.getId(),
                job.getTenantId(),
                job.getOrganizationName(),
                job.getStatus() != null ? job.getStatus().name() : ExecutionStatus.PENDING.name(),
                scoreInfo,
                parseResultJson(job.getResultJson()),
                job.getErrorMessage(),
                epochToLocalDateTime(job.getCreatedAt()),
                epochToLocalDateTime(job.getStartedAt()),
                epochToLocalDateTime(job.getCompletedAt())
        );
    }

    /**
     * Epoch milliseconds를 LocalDateTime으로 변환
     */
    private static LocalDateTime epochToLocalDateTime(Long epochMillis) {
        if (epochMillis == null) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }

    /**
     * 결과 JSON 문자열을 Map으로 변환
     * TODO: 실제 파싱이 필요한 경우 ObjectMapper를 사용하여 구현
     */
    private static Map<String, Object> parseResultJson(String resultJson) {
        if (resultJson == null || resultJson.isEmpty()) {
            return null;
        }
        // 간단한 구현을 위해 null 반환
        // 필요시 ObjectMapper를 사용하여 JSON 파싱
        return null;
    }
}
