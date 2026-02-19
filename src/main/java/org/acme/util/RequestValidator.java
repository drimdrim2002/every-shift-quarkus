package org.acme.util;

import org.acme.api.dto.PlanningRequest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        Set<String> knownEmployeeIds = new HashSet<>();
        if (request.employees() != null) {
            for (PlanningRequest.EmployeeInfo employee : request.employees()) {
                if (employee == null || employee.employeeId() == null || employee.employeeId().isBlank()) {
                    continue;
                }
                knownEmployeeIds.add(employee.employeeId());
            }
        }

        // History/Undesirable 검증 (선택사항이지만 null 체크)
        if (request.history() == null) {
            errors.add("History list cannot be null (use empty array if no history)");
        }

        if (request.undesirable() == null) {
            errors.add("Undesirable list cannot be null (use empty array if no undesirable shifts)");
        } else {
            for (int i = 0; i < request.undesirable().size(); i++) {
                PlanningRequest.AssignmentInfo assignment = request.undesirable().get(i);
                if (assignment == null) {
                    errors.add("Undesirable[" + i + "] cannot be null");
                    continue;
                }
                if (assignment.employeeId() == null || assignment.employeeId().isBlank()) {
                    errors.add("Undesirable[" + i + "].employee_id is required");
                } else if (!knownEmployeeIds.isEmpty() && !knownEmployeeIds.contains(assignment.employeeId())) {
                    errors.add("Undesirable[" + i + "].employee_id must exist in employees list");
                }
                if (assignment.date() == null) {
                    errors.add("Undesirable[" + i + "].date is required");
                }
                // Note: undesirable.shift_id / undesirable.is_locked are accepted for compatibility,
                // but are ignored by current solver constraints.
            }
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
