package org.acme.util;

import org.acme.api.dto.PlanningRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * PlanningRequest 유효성 검증 유틸리티
 */
public class RequestValidator {

    /**
     * PlanningRequest 유효성 검증
     *
     * @throws ValidationException 유효성 검증 실패 시
     */
    public static void validate(PlanningRequest request) throws ValidationException {
        List<String> errors = new ArrayList<>();

        if (request == null) {
            throw new ValidationException("Request body is required");
        }

        // Organization 검증
        if (request.organization() == null) {
            errors.add("Organization information is required");
        } else {
            if (request.organization().id() == null || request.organization().id().isBlank()) {
                errors.add("Organization ID is required");
            }
            if (request.organization().name() == null || request.organization().name().isBlank()) {
                errors.add("Organization name is required");
            }
            if (request.organization().shifts() == null || request.organization().shifts().isEmpty()) {
                errors.add("At least one shift definition is required");
            }
        }

        // Employees 검증
        if (request.employees() == null || request.employees().isEmpty()) {
            errors.add("At least one employee is required");
        }

        // History/Undesirable 검증 (선택사항이지만 null 체크)
        if (request.history() == null) {
            errors.add("History list cannot be null (use empty array if no history)");
        }

        if (request.undesirable() == null) {
            errors.add("Undesirable list cannot be null (use empty array if no undesirable shifts)");
        }

        // Requirements 검증
        if (request.requirements() == null || request.requirements().isEmpty()) {
            errors.add("Requirements are required");
        }

        if (!errors.isEmpty()) {
            throw new ValidationException("Validation failed: " + String.join(", ", errors));
        }
    }

    /**
     * 유효성 검증 예외
     */
    public static class ValidationException extends Exception {
        public ValidationException(String message) {
            super(message);
        }
    }
}
