package org.acme.solver.move;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.acme.solver.score.DependencyDimension;

/**
 * 제약별 dependency metadata를 합성한 move 영향 범위입니다.
 */
public final class MoveImpact {

    private final int[] changedShiftIndexes;
    private final int[] oldEmployeeIndexes;
    private final int[] newEmployeeIndexes;
    private final int[] affectedEmployeeIndexes;
    private final int[] relationEmployeeIndexes;
    private final int[] fairnessEmployeeIndexes;
    private final int[] mutabilityCheckedShiftIndexes;
    private final Set<LocalDate> actualDates;
    private final Set<LocalDate> logicalDates;
    private final Set<YearMonth> actualMonths;
    private final List<RestWindow> restWindows;
    private final Set<DependencyDimension> dependencyDimensions;
    private final Map<String, ConstraintImpact> impactByEvaluatorId;

    MoveImpact(
            int[] changedShiftIndexes,
            int[] oldEmployeeIndexes,
            int[] newEmployeeIndexes,
            int[] affectedEmployeeIndexes,
            int[] relationEmployeeIndexes,
            int[] fairnessEmployeeIndexes,
            int[] mutabilityCheckedShiftIndexes,
            Set<LocalDate> actualDates,
            Set<LocalDate> logicalDates,
            Set<YearMonth> actualMonths,
            List<RestWindow> restWindows,
            Set<DependencyDimension> dependencyDimensions,
            Map<String, ConstraintImpact> impactByEvaluatorId) {
        this.changedShiftIndexes = copy(changedShiftIndexes);
        this.oldEmployeeIndexes = copy(oldEmployeeIndexes);
        this.newEmployeeIndexes = copy(newEmployeeIndexes);
        this.affectedEmployeeIndexes = copy(affectedEmployeeIndexes);
        this.relationEmployeeIndexes = copy(relationEmployeeIndexes);
        this.fairnessEmployeeIndexes = copy(fairnessEmployeeIndexes);
        this.mutabilityCheckedShiftIndexes = copy(mutabilityCheckedShiftIndexes);
        this.actualDates = Set.copyOf(actualDates);
        this.logicalDates = Set.copyOf(logicalDates);
        this.actualMonths = Set.copyOf(actualMonths);
        this.restWindows = List.copyOf(restWindows);
        this.dependencyDimensions = Set.copyOf(dependencyDimensions);
        this.impactByEvaluatorId = Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(impactByEvaluatorId));
    }

    public int[] changedShiftIndexes() {
        return copy(changedShiftIndexes);
    }

    public int[] oldEmployeeIndexes() {
        return copy(oldEmployeeIndexes);
    }

    public int[] newEmployeeIndexes() {
        return copy(newEmployeeIndexes);
    }

    public int[] affectedEmployeeIndexes() {
        return copy(affectedEmployeeIndexes);
    }

    public int[] relationEmployeeIndexes() {
        return copy(relationEmployeeIndexes);
    }

    public int[] fairnessEmployeeIndexes() {
        return copy(fairnessEmployeeIndexes);
    }

    public int[] mutabilityCheckedShiftIndexes() {
        return copy(mutabilityCheckedShiftIndexes);
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

    public Set<DependencyDimension> dependencyDimensions() {
        return dependencyDimensions;
    }

    public Map<String, ConstraintImpact> impactByEvaluatorId() {
        return impactByEvaluatorId;
    }

    public ConstraintImpact forEvaluator(String evaluatorId) {
        ConstraintImpact impact = impactByEvaluatorId.get(Objects.requireNonNull(evaluatorId, "evaluatorId"));
        if (impact == null) {
            throw new IllegalArgumentException("dependency metadata가 없는 evaluator입니다: " + evaluatorId);
        }
        return impact;
    }

    private static int[] copy(int[] source) {
        return Arrays.copyOf(source, source.length);
    }
}
