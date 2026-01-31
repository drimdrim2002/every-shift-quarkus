package org.acme.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * POST /api/solve 응답 DTO
 */
@RegisterForReflection
public record SolveResponse(
        @JsonProperty("execution_id") String executionId,
        @JsonProperty("status") String status,
        @JsonProperty("message") String message
) {
    public static SolveResponse created(String executionId) {
        return new SolveResponse(executionId, "PENDING", "Solver execution queued");
    }
}
