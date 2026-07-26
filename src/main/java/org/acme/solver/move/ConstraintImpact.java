package org.acme.solver.move;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.acme.solver.score.ConstraintDependencyMetadata;

/** 한 evaluator가 한 move에서 다시 계산해야 하는 정확한 범위입니다. */
public final class ConstraintImpact {

    private final ConstraintDependencyMetadata metadata;
    private final int[] evaluationUnitIndexes;
    private final int[] employeeIndexes;
    private final int[] shiftIndexes;
    private final Set<LocalDate> actualDates;
    private final Set<LocalDate> logicalDates;
    private final Set<YearMonth> actualMonths;
    private final List<RestWindow> restWindows;

    ConstraintImpact(
            ConstraintDependencyMetadata metadata,
            int[] evaluationUnitIndexes,
            int[] employeeIndexes,
            int[] shiftIndexes,
            Set<LocalDate> actualDates,
            Set<LocalDate> logicalDates,
            Set<YearMonth> actualMonths,
            List<RestWindow> restWindows) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.evaluationUnitIndexes = Arrays.copyOf(evaluationUnitIndexes, evaluationUnitIndexes.length);
        this.employeeIndexes = Arrays.copyOf(employeeIndexes, employeeIndexes.length);
        this.shiftIndexes = Arrays.copyOf(shiftIndexes, shiftIndexes.length);
        this.actualDates = Set.copyOf(actualDates);
        this.logicalDates = Set.copyOf(logicalDates);
        this.actualMonths = Set.copyOf(actualMonths);
        this.restWindows = List.copyOf(restWindows);
    }

    public ConstraintDependencyMetadata metadata() {
        return metadata;
    }

    public int[] evaluationUnitIndexes() {
        return Arrays.copyOf(evaluationUnitIndexes, evaluationUnitIndexes.length);
    }

    public int[] employeeIndexes() {
        return Arrays.copyOf(employeeIndexes, employeeIndexes.length);
    }

    public int[] shiftIndexes() {
        return Arrays.copyOf(shiftIndexes, shiftIndexes.length);
    }

    public Set<LocalDate> actualDates() {
        return actualDates;
    }

    public Set<LocalDate> logicalDates() {
        return logicalDates;
    }

    public Set<YearMonth> actualMonths() {
        return actualMonths;
    }

    public List<RestWindow> restWindows() {
        return restWindows;
    }
}
