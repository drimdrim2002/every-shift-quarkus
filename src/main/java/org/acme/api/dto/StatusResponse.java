package org.acme.api.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

import org.acme.model.ExecutionStatus;
import org.acme.model.JobExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.runtime.annotations.RegisterForReflection;

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
        @JsonProperty("completed_at") LocalDateTime completedAt) {
    public record ScoreInfo(
            @JsonProperty("hard_score") Integer hardScore,
            @JsonProperty("undesired_soft_score") Integer undesiredSoftScore,
            @JsonProperty("fair_soft_score") Integer fairSoftScore,
            @JsonProperty("desired_soft_score") Integer desiredSoftScore,
            @JsonProperty("legacy_soft_score_total") Integer legacySoftScoreTotal) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(StatusResponse.class);

    /**
     * JobExecution 엔티티로부터 StatusResponse 생성
     */
    public static StatusResponse from(JobExecution job, ObjectMapper objectMapper) {
        ScoreInfo scoreInfo = null;
        if (hasBendableSoftScores(job)) {
            scoreInfo = new ScoreInfo(
                    job.getHardScore(),
                    job.getUndesiredSoftScore(),
                    job.getFairSoftScore(),
                    job.getDesiredSoftScore(),
                    null);
        } else if (job.getHardScore() != null || job.getSoftScore() != null) {
            scoreInfo = new ScoreInfo(
                    job.getHardScore(),
                    null,
                    null,
                    null,
                    job.getSoftScore());
        }

        return new StatusResponse(
                job.getId(),
                job.getTenantId(),
                job.getOrganizationName(),
                job.getStatus() != null ? job.getStatus().name() : ExecutionStatus.PENDING.name(),
                scoreInfo,
                parseResultJson(job.getResultJson(), objectMapper),
                job.getErrorMessage(),
                epochToLocalDateTime(job.getCreatedAt()),
                epochToLocalDateTime(job.getStartedAt()),
                epochToLocalDateTime(job.getCompletedAt()));
    }

    private static boolean hasBendableSoftScores(JobExecution job) {
        return job.getUndesiredSoftScore() != null
                || job.getFairSoftScore() != null
                || job.getDesiredSoftScore() != null;
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
    private static Map<String, Object> parseResultJson(String resultJson, ObjectMapper objectMapper) {
        if (resultJson == null || resultJson.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(resultJson, Map.class);
        } catch (JsonProcessingException e) {
            LOG.warn("Failed to parse result JSON: {}", e.getMessage());
            return null;
        }
        // 필요시 ObjectMapper를 사용하여 JSON 파싱
        // return null;
    }
}
