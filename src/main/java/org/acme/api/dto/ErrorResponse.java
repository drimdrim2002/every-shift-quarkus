package org.acme.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * 에러 응답 DTO
 */
@RegisterForReflection
public record ErrorResponse(
        @JsonProperty("error") String error
) {
    public static ErrorResponse of(String message) {
        return new ErrorResponse(message);
    }
}
