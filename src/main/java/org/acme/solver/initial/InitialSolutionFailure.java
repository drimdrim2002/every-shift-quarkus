package org.acme.solver.initial;

import java.util.List;
import java.util.Objects;

/** 직원 이름이나 원본 입력을 노출하지 않는 초기해 실패 진단입니다. */
public record InitialSolutionFailure(
        InitialSolutionFailureCode code,
        List<Integer> shiftIndexes,
        List<Integer> employeeIndexes,
        String message) {

    public InitialSolutionFailure {
        Objects.requireNonNull(code, "code");
        shiftIndexes = List.copyOf(Objects.requireNonNull(shiftIndexes, "shiftIndexes"));
        employeeIndexes = List.copyOf(Objects.requireNonNull(employeeIndexes, "employeeIndexes"));
        Objects.requireNonNull(message, "message");
    }
}
