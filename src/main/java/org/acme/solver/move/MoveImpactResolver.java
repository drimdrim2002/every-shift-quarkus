package org.acme.solver.move;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.acme.solver.core.PlanningProblem;
import org.acme.solver.score.ConstraintDependencyMetadata;
import org.acme.solver.score.DependencyDimension;
import org.acme.solver.score.EvaluationGranularity;

/** 제약 metadata의 합집합으로 move 영향 범위를 결정합니다. */
public final class MoveImpactResolver {

    private final PlanningProblem problem;
    private final List<ConstraintDependencyMetadata> metadata;
    private final PreceptorRelationIndex relationIndex;

    public MoveImpactResolver(
            PlanningProblem problem, List<ConstraintDependencyMetadata> metadata) {
        this.problem = Objects.requireNonNull(problem, "problem");
        this.metadata = List.copyOf(Objects.requireNonNull(metadata, "metadata"));
        this.relationIndex = new PreceptorRelationIndex(problem);
        Set<String> ids = new java.util.HashSet<>();
        for (ConstraintDependencyMetadata dependency : this.metadata) {
            if (!ids.add(dependency.evaluatorId())) {
                throw new IllegalArgumentException(
                        "중복 dependency evaluatorId입니다: " + dependency.evaluatorId());
            }
        }
    }

    public MoveImpact resolve(Move move) {
        Objects.requireNonNull(move, "move");
        List<AssignmentChange> changes = move.changes();
        if (changes.isEmpty()) {
            throw new IllegalArgumentException("move에는 하나 이상의 change가 필요합니다.");
        }

        TreeSet<Integer> shifts = new TreeSet<>();
        TreeSet<Integer> oldEmployees = new TreeSet<>();
        TreeSet<Integer> newEmployees = new TreeSet<>();
        TreeSet<Integer> affectedEmployees = new TreeSet<>();
        TreeSet<Integer> relationEmployees = new TreeSet<>();
        Set<LocalDate> baseActualDates = new LinkedHashSet<>();
        Set<LocalDate> baseLogicalDates = new LinkedHashSet<>();
        Set<YearMonth> baseMonths = new LinkedHashSet<>();

        for (AssignmentChange change : changes) {
            if (change.shiftIndex() >= problem.shiftCount()
                    || change.oldEmployeeIndex() >= problem.employeeCount()
                    || change.newEmployeeIndex() >= problem.employeeCount()) {
                throw new IllegalArgumentException("move change가 PlanningProblem 범위를 벗어났습니다: " + change);
            }
            if (!problem.isMutableShift(change.shiftIndex())) {
                throw new IllegalArgumentException(
                        "pinned/immutable shift는 영향 범위에 들어올 수 없습니다: " + change.shiftIndex());
            }
            shifts.add(change.shiftIndex());
            oldEmployees.add(change.oldEmployeeIndex());
            newEmployees.add(change.newEmployeeIndex());
            affectedEmployees.add(change.oldEmployeeIndex());
            affectedEmployees.add(change.newEmployeeIndex());
            relationEmployees.addAll(relationIndex.employeesFor(change.oldEmployeeIndex()));
            relationEmployees.addAll(relationIndex.employeesFor(change.newEmployeeIndex()));

            PlanningProblem.ShiftData shift = problem.shifts().get(change.shiftIndex());
            baseActualDates.add(shift.start().toLocalDate());
            baseLogicalDates.add(shift.logicalDate());
            baseMonths.add(YearMonth.from(shift.start()));
        }

        Map<String, ConstraintImpact> perEvaluator = new LinkedHashMap<>();
        Set<DependencyDimension> unionDimensions = new LinkedHashSet<>();
        Set<LocalDate> unionActualDates = new LinkedHashSet<>();
        Set<LocalDate> unionLogicalDates = new LinkedHashSet<>();
        Set<YearMonth> unionMonths = new LinkedHashSet<>();
        List<RestWindow> unionRestWindows = new ArrayList<>();

        for (ConstraintDependencyMetadata dependency : metadata) {
            unionDimensions.addAll(dependency.dimensions());
            Set<LocalDate> actualDates = dependency.dimensions().contains(DependencyDimension.ACTUAL_DATE)
                    ? baseActualDates
                    : Set.of();
            Set<YearMonth> months = dependency.dimensions().contains(DependencyDimension.ACTUAL_MONTH)
                    ? baseMonths
                    : Set.of();
            Set<LocalDate> logicalDates = expandLogicalDates(baseLogicalDates, dependency);
            List<RestWindow> restWindows = restWindows(shifts, dependency);

            TreeSet<Integer> evaluatorEmployees = new TreeSet<>(affectedEmployees);
            if (dependency.dimensions().contains(DependencyDimension.PRECEPTOR_RELATION_GROUP)) {
                evaluatorEmployees.addAll(relationEmployees);
            }
            int[] evaluationUnits = dependency.granularity() == EvaluationGranularity.SHIFT
                    ? toIntArray(shifts)
                    : toIntArray(evaluatorEmployees);
            ConstraintImpact impact = new ConstraintImpact(
                    dependency,
                    evaluationUnits,
                    toIntArray(evaluatorEmployees),
                    toIntArray(shifts),
                    actualDates,
                    logicalDates,
                    months,
                    restWindows);
            perEvaluator.put(dependency.evaluatorId(), impact);
            unionActualDates.addAll(actualDates);
            unionLogicalDates.addAll(logicalDates);
            unionMonths.addAll(months);
            unionRestWindows.addAll(restWindows);
        }

        return new MoveImpact(
                toIntArray(shifts),
                toIntArray(oldEmployees),
                toIntArray(newEmployees),
                toIntArray(affectedEmployees),
                toIntArray(relationEmployees),
                toIntArray(affectedEmployees),
                toIntArray(shifts),
                unionActualDates,
                unionLogicalDates,
                unionMonths,
                unionRestWindows,
                unionDimensions,
                perEvaluator);
    }

    public PreceptorRelationIndex relationIndex() {
        return relationIndex;
    }

    private Set<LocalDate> expandLogicalDates(
            Set<LocalDate> baseDates, ConstraintDependencyMetadata dependency) {
        if (!dependency.dimensions().contains(DependencyDimension.LOGICAL_DATE)) {
            return Set.of();
        }
        Set<LocalDate> result = new LinkedHashSet<>();
        for (LocalDate baseDate : baseDates) {
            for (int offset = -dependency.logicalDaysBefore();
                    offset <= dependency.logicalDaysAfter(); offset++) {
                result.add(baseDate.plusDays(offset));
            }
        }
        return result;
    }

    private List<RestWindow> restWindows(
            Collection<Integer> shifts, ConstraintDependencyMetadata dependency) {
        if (!dependency.dimensions().contains(DependencyDimension.ADJACENT_REST_WINDOW)) {
            return List.of();
        }
        List<RestWindow> result = new ArrayList<>(shifts.size());
        for (int shiftIndex : shifts) {
            PlanningProblem.ShiftData shift = problem.shifts().get(shiftIndex);
            result.add(new RestWindow(
                    shift.start().minus(dependency.restBefore()),
                    shift.end().plus(dependency.restAfter())));
        }
        return result;
    }

    private static int[] toIntArray(Collection<Integer> values) {
        int[] result = new int[values.size()];
        int index = 0;
        for (int value : values) {
            result[index++] = value;
        }
        return result;
    }
}
