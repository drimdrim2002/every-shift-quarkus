package org.acme.solver.initial;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.acme.solver.ShiftDateMatcher;
import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.score.FullScoreCalculator;
import org.acme.solver.score.RosterIndex;

/**
 * 고정 배정을 먼저 적용한 뒤 minimum-candidate/regret-2로 complete 초기해를 만듭니다.
 *
 * <p>생성 중인 {@code -1} assignment 배열은 이 클래스 밖으로 노출하지 않고,
 * complete가 된 뒤에만 {@link RosterSolution}과 {@link RosterScore}를 생성합니다.</p>
 */
public final class InitialSolutionBuilder {

    private static final int MINIMUM_REST_MINUTES = 12 * 60;
    private static final int NIGHT_TO_DAY_REST_MINUTES = 32 * 60;
    private static final int POST_NIGHT_RECOVERY_MINUTES = 48 * 60;
    private static final int MAX_MONTHLY_NIGHTS = 15;

    private final FullScoreCalculator fullScoreCalculator;

    public InitialSolutionBuilder() {
        this(new FullScoreCalculator());
    }

    public InitialSolutionBuilder(FullScoreCalculator fullScoreCalculator) {
        this.fullScoreCalculator = Objects.requireNonNull(fullScoreCalculator, "fullScoreCalculator");
    }

    public InitialSolutionResult build(PlanningProblem problem) {
        return build(problem, () -> false);
    }

    public InitialSolutionResult build(PlanningProblem problem, BooleanSupplier interrupted) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(interrupted, "interrupted");
        if (problem.employeeCount() == 0) {
            return failure(InitialSolutionFailureCode.NO_EMPLOYEE, List.of(), List.of(),
                    "배정 가능한 직원이 없습니다.");
        }

        RelationGraph relationGraph;
        try {
            relationGraph = RelationGraph.create(problem);
        } catch (RelationGraphException exception) {
            return failure(exception.code, List.of(), exception.employeeIndexes, exception.getMessage());
        }

        int[] assignments = new int[problem.shiftCount()];
        Arrays.fill(assignments, -1);
        for (int shiftIndex : problem.pinnedShiftIndexes()) {
            int employeeIndex = problem.initialEmployeeIndex(shiftIndex);
            if (employeeIndex < 0) {
                return failure(InitialSolutionFailureCode.UNASSIGNED_FIXED_SHIFT,
                        List.of(shiftIndex), List.of(), "고정 shift에 기존 배정이 없습니다.");
            }
            if (!canWorkShiftCode(problem, employeeIndex, shiftIndex)) {
                return failure(InitialSolutionFailureCode.FIXED_ASSIGNMENT_CONFLICT,
                        List.of(shiftIndex), List.of(employeeIndex),
                        "고정 직원이 해당 shift code를 수행할 수 없습니다.");
            }
            assignments[shiftIndex] = employeeIndex;
        }

        PartialRosterScorer scorer = new PartialRosterScorer(problem);
        PartialScore fixedScore = scorer.score(assignments);
        if (hasMonotonicFixedConflict(problem, assignments)) {
            return failure(InitialSolutionFailureCode.FIXED_ASSIGNMENT_CONFLICT,
                    indexesOfAssigned(assignments), distinctAssignedEmployees(assignments),
                    "고정 배정 자체에 hard 제약 충돌이 있습니다.");
        }

        Map<SlotKey, List<Integer>> shiftsBySlot = indexShiftsBySlot(problem);
        InitialSolutionFailure fixedRelationFailure = validateFixedRelations(
                problem, assignments, relationGraph, shiftsBySlot);
        if (fixedRelationFailure != null) {
            return InitialSolutionResult.failure(fixedRelationFailure);
        }

        PartialScore currentScore = fixedScore;
        while (containsUnassigned(assignments)) {
            if (interrupted.getAsBoolean()) {
                return failure(InitialSolutionFailureCode.INTERRUPTED, List.of(), List.of(),
                        "초기해 생성이 종료 요청으로 중단되었습니다.");
            }

            ForcedRelation forced = findForcedRelation(
                    problem, assignments, relationGraph, shiftsBySlot);
            if (forced != null) {
                List<Placement> candidates = enumerateMappings(
                        problem,
                        assignments,
                        forced.missingEmployees(),
                        forced.availableShiftIndexes(),
                        -1,
                        scorer,
                        currentScore);
                candidates = preferHardFreeThenMinimumLoss(candidates, currentScore);
                if (candidates.isEmpty()) {
                    return failure(InitialSolutionFailureCode.NO_ATOMIC_RELATION_ASSIGNMENT,
                            forced.availableShiftIndexes(), toIntegerList(forced.missingEmployees()),
                            "고정 relation group을 같은 날짜와 교대에 완성할 수 없습니다.");
                }
                Placement selected = candidates.getFirst();
                apply(assignments, selected);
                scorer.commit(selected);
                currentScore = selected.score();
                continue;
            }

            ShiftChoice selectedChoice = null;
            for (int shiftIndex = 0; shiftIndex < assignments.length; shiftIndex++) {
                if (assignments[shiftIndex] >= 0) {
                    continue;
                }
                List<Placement> candidates = candidatesForShift(
                        problem, assignments, relationGraph, shiftsBySlot, shiftIndex, scorer, currentScore);
                if (candidates.isEmpty()) {
                    return failure(InitialSolutionFailureCode.NO_ASSIGNABLE_EMPLOYEE,
                            List.of(shiftIndex), List.of(),
                            "shift code와 relation 원자성을 만족하는 직원 후보가 없습니다.");
                }
                candidates = preferHardFreeThenMinimumLoss(candidates, currentScore);
                ShiftChoice choice = new ShiftChoice(shiftIndex, candidates, Regret.between(candidates));
                if (selectedChoice == null || choice.isPreferredTo(selectedChoice)) {
                    selectedChoice = choice;
                }
            }

            Placement selected = Objects.requireNonNull(selectedChoice, "selectedChoice").candidates().getFirst();
            apply(assignments, selected);
            scorer.commit(selected);
            currentScore = selected.score();
        }

        RosterSolution unscored = new RosterSolution(
                problem.employeeCount(), assignments, RosterScore.of(0, 0, 0, 0, 0));
        RosterScore verifiedScore = fullScoreCalculator.calculateScore(problem, unscored);
        return InitialSolutionResult.success(new RosterSolution(
                problem.employeeCount(), assignments, verifiedScore));
    }

    private static List<Placement> candidatesForShift(
            PlanningProblem problem,
            int[] assignments,
            RelationGraph relationGraph,
            Map<SlotKey, List<Integer>> shiftsBySlot,
            int targetShiftIndex,
            PartialRosterScorer scorer,
            PartialScore currentScore) {
        PlanningProblem.ShiftData targetShift = problem.shifts().get(targetShiftIndex);
        SlotKey slot = SlotKey.of(targetShift);
        List<Integer> availableShifts = shiftsBySlot.get(slot).stream()
                .filter(index -> assignments[index] < 0)
                .toList();
        List<Placement> result = new ArrayList<>();

        for (int[] group : relationGraph.groups()) {
            if (group.length > availableShifts.size()) {
                continue;
            }
            boolean codeCompatible = true;
            for (int employeeIndex : group) {
                if (!canWorkShiftCode(problem, employeeIndex, targetShiftIndex)) {
                    codeCompatible = false;
                    break;
                }
            }
            if (!codeCompatible) {
                continue;
            }
            result.addAll(enumerateMappings(
                    problem, assignments, group, availableShifts, targetShiftIndex, scorer, currentScore));
        }
        result.sort(Placement.BEST_FIRST);
        return result;
    }

    private static List<Placement> enumerateMappings(
            PlanningProblem problem,
            int[] assignments,
            int[] employeeIndexes,
            List<Integer> availableShiftIndexes,
            int requiredShiftIndex,
            PartialRosterScorer scorer,
            PartialScore currentScore) {
        if (employeeIndexes.length == 0 || employeeIndexes.length > availableShiftIndexes.size()) {
            return List.of();
        }
        List<Placement> result = new ArrayList<>();
        int[] selectedShifts = new int[employeeIndexes.length];
        boolean[] used = new boolean[availableShiftIndexes.size()];
        enumerateMappingsRecursive(
                problem, assignments, employeeIndexes, availableShiftIndexes, requiredShiftIndex,
                scorer, currentScore, 0, selectedShifts, used, result);
        result.sort(Placement.BEST_FIRST);
        return result;
    }

    private static void enumerateMappingsRecursive(
            PlanningProblem problem,
            int[] assignments,
            int[] employeeIndexes,
            List<Integer> availableShiftIndexes,
            int requiredShiftIndex,
            PartialRosterScorer scorer,
            PartialScore currentScore,
            int employeeOffset,
            int[] selectedShifts,
            boolean[] used,
            List<Placement> result) {
        if (employeeOffset == employeeIndexes.length) {
            if (requiredShiftIndex >= 0 && Arrays.stream(selectedShifts).noneMatch(i -> i == requiredShiftIndex)) {
                return;
            }
            int[] shiftCopy = Arrays.copyOf(selectedShifts, selectedShifts.length);
            int[] employeeCopy = Arrays.copyOf(employeeIndexes, employeeIndexes.length);
            PartialScore score = scorer.scoreAfterAdding(
                    assignments, shiftCopy, employeeCopy, currentScore);
            result.add(new Placement(shiftCopy, employeeCopy, score));
            return;
        }

        int employeeIndex = employeeIndexes[employeeOffset];
        for (int availableOffset = 0; availableOffset < availableShiftIndexes.size(); availableOffset++) {
            if (used[availableOffset]) {
                continue;
            }
            int shiftIndex = availableShiftIndexes.get(availableOffset);
            if (!canWorkShiftCode(problem, employeeIndex, shiftIndex)) {
                continue;
            }
            used[availableOffset] = true;
            selectedShifts[employeeOffset] = shiftIndex;
            enumerateMappingsRecursive(
                    problem, assignments, employeeIndexes, availableShiftIndexes, requiredShiftIndex,
                    scorer, currentScore, employeeOffset + 1, selectedShifts, used, result);
            used[availableOffset] = false;
        }
    }

    private static List<Placement> preferHardFreeThenMinimumLoss(
            List<Placement> source, PartialScore currentScore) {
        if (source.isEmpty()) {
            return source;
        }
        boolean hasHardFree = source.stream()
                .anyMatch(candidate -> candidate.score().hardScore() >= currentScore.hardScore());
        long bestHard = source.stream().mapToLong(candidate -> candidate.score().hardScore()).max().orElseThrow();
        List<Placement> filtered = source.stream()
                .filter(candidate -> hasHardFree
                        ? candidate.score().hardScore() >= currentScore.hardScore()
                        : candidate.score().hardScore() == bestHard)
                .sorted(Placement.BEST_FIRST)
                .toList();
        return filtered;
    }

    private static InitialSolutionFailure validateFixedRelations(
            PlanningProblem problem,
            int[] assignments,
            RelationGraph relationGraph,
            Map<SlotKey, List<Integer>> shiftsBySlot) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        for (int shiftIndex = 0; shiftIndex < assignments.length; shiftIndex++) {
            if (assignments[shiftIndex] >= 0) {
                dates.add(problem.shifts().get(shiftIndex).start().toLocalDate());
            }
        }
        for (LocalDate date : dates) {
            for (int[] group : relationGraph.groups()) {
                if (group.length == 1) {
                    continue;
                }
                Set<SlotKey> occupiedSlots = new LinkedHashSet<>();
                List<Integer> occupiedEmployees = new ArrayList<>();
                for (int employeeIndex : group) {
                    int shiftIndex = assignedShiftOnDate(problem, assignments, employeeIndex, date);
                    if (shiftIndex >= 0) {
                        occupiedSlots.add(SlotKey.of(problem.shifts().get(shiftIndex)));
                        occupiedEmployees.add(employeeIndex);
                    }
                }
                if (occupiedSlots.size() > 1) {
                    return new InitialSolutionFailure(
                            InitialSolutionFailureCode.FIXED_ASSIGNMENT_CONFLICT,
                            List.of(), occupiedEmployees,
                            "고정 relation group 직원이 같은 날짜의 서로 다른 교대에 배정되었습니다.");
                }
                if (occupiedSlots.size() == 1) {
                    SlotKey slot = occupiedSlots.iterator().next();
                    long unassigned = shiftsBySlot.getOrDefault(slot, List.of()).stream()
                            .filter(index -> assignments[index] < 0)
                            .count();
                    if (occupiedEmployees.size() + unassigned < group.length) {
                        return new InitialSolutionFailure(
                                InitialSolutionFailureCode.NO_ATOMIC_RELATION_ASSIGNMENT,
                                shiftsBySlot.getOrDefault(slot, List.of()),
                                toIntegerList(group),
                                "고정 relation group을 완성할 shift 용량이 부족합니다.");
                    }
                }
            }
        }
        return null;
    }

    private static ForcedRelation findForcedRelation(
            PlanningProblem problem,
            int[] assignments,
            RelationGraph graph,
            Map<SlotKey, List<Integer>> shiftsBySlot) {
        for (var slotEntry : shiftsBySlot.entrySet()) {
            SlotKey slot = slotEntry.getKey();
            for (int[] group : graph.groups()) {
                if (group.length == 1) {
                    continue;
                }
                List<Integer> missing = new ArrayList<>();
                int present = 0;
                boolean elsewhere = false;
                for (int employeeIndex : group) {
                    int shiftIndex = assignedShiftOnDate(problem, assignments, employeeIndex, slot.date());
                    if (shiftIndex < 0) {
                        missing.add(employeeIndex);
                    } else if (SlotKey.of(problem.shifts().get(shiftIndex)).equals(slot)) {
                        present++;
                    } else {
                        elsewhere = true;
                    }
                }
                if (!elsewhere && present > 0 && !missing.isEmpty()) {
                    List<Integer> available = slotEntry.getValue().stream()
                            .filter(index -> assignments[index] < 0)
                            .toList();
                    return new ForcedRelation(toIntArray(missing), available);
                }
            }
        }
        return null;
    }

    private static void apply(int[] assignments, Placement placement) {
        for (int index = 0; index < placement.shiftIndexes().length; index++) {
            int shiftIndex = placement.shiftIndexes()[index];
            if (assignments[shiftIndex] >= 0) {
                throw new IllegalStateException("이미 배정된 shift에 초기 placement를 적용할 수 없습니다: " + shiftIndex);
            }
            assignments[shiftIndex] = placement.employeeIndexes()[index];
        }
    }

    private static int assignedShiftOnDate(
            PlanningProblem problem, int[] assignments, int employeeIndex, LocalDate date) {
        int found = -1;
        for (int shiftIndex = 0; shiftIndex < assignments.length; shiftIndex++) {
            if (assignments[shiftIndex] == employeeIndex
                    && problem.shifts().get(shiftIndex).start().toLocalDate().equals(date)) {
                if (found >= 0) {
                    return found;
                }
                found = shiftIndex;
            }
        }
        return found;
    }

    private static boolean canWorkShiftCode(
            PlanningProblem problem, int employeeIndex, int shiftIndex) {
        String shiftCode = normalize(problem.shifts().get(shiftIndex).shiftCode());
        for (String available : problem.employees().get(employeeIndex).availableShiftCodes()) {
            if (normalize(available).equals(shiftCode)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean containsUnassigned(int[] assignments) {
        for (int assignment : assignments) {
            if (assignment < 0) {
                return true;
            }
        }
        return false;
    }

    /** 이후 배정으로 해소될 수 없는 고정 hard 충돌만 조기 실패로 처리합니다. */
    private static boolean hasMonotonicFixedConflict(
            PlanningProblem problem, int[] assignments) {
        List<List<Integer>> byEmployee = new ArrayList<>(problem.employeeCount());
        for (int employeeIndex = 0; employeeIndex < problem.employeeCount(); employeeIndex++) {
            byEmployee.add(new ArrayList<>());
        }
        for (int shiftIndex = 0; shiftIndex < assignments.length; shiftIndex++) {
            int employeeIndex = assignments[shiftIndex];
            if (employeeIndex < 0) {
                continue;
            }
            PlanningProblem.ShiftData shift = problem.shifts().get(shiftIndex);
            if (!problem.employees().get(employeeIndex).skillSet().contains(shift.requiredSkill())) {
                return true;
            }
            byEmployee.get(employeeIndex).add(shiftIndex);
        }
        for (int employeeIndex = 0; employeeIndex < byEmployee.size(); employeeIndex++) {
            List<Integer> shifts = byEmployee.get(employeeIndex);
            Map<LocalDate, Integer> nightsByLogicalDate = new HashMap<>();
            Map<YearMonth, Integer> nightsByMonth = new HashMap<>();
            for (int shiftIndex : shifts) {
                PlanningProblem.ShiftData shift = problem.shifts().get(shiftIndex);
                if (PartialRosterScorer.isNight(shift)) {
                    nightsByLogicalDate.merge(shift.logicalDate(), 1, Math::addExact);
                    nightsByMonth.merge(YearMonth.from(shift.start()), 1, Math::addExact);
                }
            }
            for (int left = 0; left < shifts.size(); left++) {
                PlanningProblem.ShiftData first = problem.shifts().get(shifts.get(left));
                for (int right = left + 1; right < shifts.size(); right++) {
                    PlanningProblem.ShiftData second = problem.shifts().get(shifts.get(right));
                    if (first.start().toLocalDate().equals(second.start().toLocalDate())) {
                        return true;
                    }
                    if (first.start().isBefore(second.end()) && second.start().isBefore(first.end())) {
                        return true;
                    }
                    long rest = PartialRosterScorer.breakMinutes(first, second);
                    if (rest >= 0L && rest < MINIMUM_REST_MINUTES) {
                        return true;
                    }
                }
            }
            for (var entry : nightsByLogicalDate.entrySet()) {
                long combinations = entry.getValue();
                for (int offset = 1; offset <= 3; offset++) {
                    combinations *= nightsByLogicalDate.getOrDefault(entry.getKey().plusDays(offset), 0);
                }
                if (combinations > 0L) {
                    return true;
                }
            }
            if (nightsByMonth.values().stream().anyMatch(count -> count > MAX_MONTHLY_NIGHTS)) {
                return true;
            }
        }
        return false;
    }

    private static Map<SlotKey, List<Integer>> indexShiftsBySlot(PlanningProblem problem) {
        Map<SlotKey, List<Integer>> mutable = new LinkedHashMap<>();
        for (int shiftIndex = 0; shiftIndex < problem.shiftCount(); shiftIndex++) {
            mutable.computeIfAbsent(SlotKey.of(problem.shifts().get(shiftIndex)), ignored -> new ArrayList<>())
                    .add(shiftIndex);
        }
        Map<SlotKey, List<Integer>> result = new LinkedHashMap<>();
        mutable.forEach((slot, indexes) -> result.put(slot, List.copyOf(indexes)));
        return result;
    }

    private static InitialSolutionResult failure(
            InitialSolutionFailureCode code,
            List<Integer> shiftIndexes,
            List<Integer> employeeIndexes,
            String message) {
        return InitialSolutionResult.failure(new InitialSolutionFailure(
                code, shiftIndexes, employeeIndexes, message));
    }

    private static List<Integer> indexesOfAssigned(int[] assignments) {
        List<Integer> result = new ArrayList<>();
        for (int shiftIndex = 0; shiftIndex < assignments.length; shiftIndex++) {
            if (assignments[shiftIndex] >= 0) {
                result.add(shiftIndex);
            }
        }
        return result;
    }

    private static List<Integer> distinctAssignedEmployees(int[] assignments) {
        Set<Integer> result = new LinkedHashSet<>();
        for (int employeeIndex : assignments) {
            if (employeeIndex >= 0) {
                result.add(employeeIndex);
            }
        }
        return List.copyOf(result);
    }

    private static List<Integer> toIntegerList(int[] values) {
        return Arrays.stream(values).boxed().toList();
    }

    private static int[] toIntArray(List<Integer> values) {
        return values.stream().mapToInt(Integer::intValue).toArray();
    }

    private record SlotKey(LocalDate date, String shiftCode) {
        static SlotKey of(PlanningProblem.ShiftData shift) {
            return new SlotKey(shift.start().toLocalDate(), normalize(shift.shiftCode()));
        }
    }

    private record ForcedRelation(int[] missingEmployees, List<Integer> availableShiftIndexes) {
    }

    private record Placement(int[] shiftIndexes, int[] employeeIndexes, PartialScore score) {
        private static final Comparator<Placement> BEST_FIRST = (left, right) -> {
            int byScore = right.score.compareTo(left.score);
            if (byScore != 0) {
                return byScore;
            }
            int byShifts = compareArrays(left.shiftIndexes, right.shiftIndexes);
            return byShifts != 0 ? byShifts : compareArrays(left.employeeIndexes, right.employeeIndexes);
        };

        private static int compareArrays(int[] left, int[] right) {
            int length = Math.min(left.length, right.length);
            for (int index = 0; index < length; index++) {
                int comparison = Integer.compare(left[index], right[index]);
                if (comparison != 0) {
                    return comparison;
                }
            }
            return Integer.compare(left.length, right.length);
        }
    }

    private record ShiftChoice(int shiftIndex, List<Placement> candidates, Regret regret) {
        boolean isPreferredTo(ShiftChoice other) {
            int byCount = Integer.compare(candidates.size(), other.candidates.size());
            if (byCount != 0) {
                return byCount < 0;
            }
            int byRegret = regret.compareTo(other.regret);
            return byRegret != 0 ? byRegret > 0 : shiftIndex < other.shiftIndex;
        }
    }

    private record Regret(boolean onlyCandidate, int firstDifferentLevel, long magnitude)
            implements Comparable<Regret> {
        static Regret between(List<Placement> candidates) {
            if (candidates.size() == 1) {
                return new Regret(true, 0, Long.MAX_VALUE);
            }
            PartialScore best = candidates.get(0).score();
            PartialScore second = candidates.get(1).score();
            for (int level = 0; level < 5; level++) {
                long delta = best.level(level) - second.level(level);
                if (delta != 0L) {
                    return new Regret(false, level, Math.abs(delta));
                }
            }
            return new Regret(false, 5, 0L);
        }

        @Override
        public int compareTo(Regret other) {
            if (onlyCandidate != other.onlyCandidate) {
                return onlyCandidate ? 1 : -1;
            }
            int byLevel = Integer.compare(other.firstDifferentLevel, firstDifferentLevel);
            return byLevel != 0 ? byLevel : Long.compare(magnitude, other.magnitude);
        }
    }

    /** partial 전용 휴리스틱이며 RosterScore로 변환하거나 외부에 노출하지 않습니다. */
    private record PartialScore(long hardScore, long soft0, long soft1, long soft2, long soft3)
            implements Comparable<PartialScore> {
        PartialScore plus(PartialScore other) {
            return new PartialScore(
                    Math.addExact(hardScore, other.hardScore),
                    Math.addExact(soft0, other.soft0),
                    Math.addExact(soft1, other.soft1),
                    Math.addExact(soft2, other.soft2),
                    Math.addExact(soft3, other.soft3));
        }

        PartialScore minus(PartialScore other) {
            return new PartialScore(
                    Math.subtractExact(hardScore, other.hardScore),
                    Math.subtractExact(soft0, other.soft0),
                    Math.subtractExact(soft1, other.soft1),
                    Math.subtractExact(soft2, other.soft2),
                    Math.subtractExact(soft3, other.soft3));
        }

        long level(int level) {
            return switch (level) {
                case 0 -> hardScore;
                case 1 -> soft0;
                case 2 -> soft1;
                case 3 -> soft2;
                case 4 -> soft3;
                default -> throw new IndexOutOfBoundsException("score level: " + level);
            };
        }

        @Override
        public int compareTo(PartialScore other) {
            for (int level = 0; level < 5; level++) {
                int comparison = Long.compare(level(level), other.level(level));
                if (comparison != 0) {
                    return comparison;
                }
            }
            return 0;
        }
    }

    /** 현재 배정된 항목만 집계하며 relation 위반은 원자적 placement가 구조적으로 차단합니다. */
    private static final class PartialRosterScorer {
        private final PlanningProblem problem;
        private final List<Set<LocalDate>> desiredDates;
        private final List<Set<LocalDate>> undesiredDates;
        private final List<List<Integer>> assignedShiftsByEmployee;
        private final PartialScore[] contributionByEmployee;

        private PartialRosterScorer(PlanningProblem problem) {
            this.problem = problem;
            this.desiredDates = availabilityDates(problem, PlanningProblem.AvailabilityKind.DESIRED);
            this.undesiredDates = availabilityDates(problem, PlanningProblem.AvailabilityKind.UNDESIRED);
            this.assignedShiftsByEmployee = new ArrayList<>(problem.employeeCount());
            this.contributionByEmployee = new PartialScore[problem.employeeCount()];
            for (int employeeIndex = 0; employeeIndex < problem.employeeCount(); employeeIndex++) {
                assignedShiftsByEmployee.add(new ArrayList<>());
                contributionByEmployee[employeeIndex] = zero();
            }
        }

        PartialScore score(int[] assignments) {
            for (List<Integer> shifts : assignedShiftsByEmployee) {
                shifts.clear();
            }
            for (int shiftIndex = 0; shiftIndex < assignments.length; shiftIndex++) {
                if (assignments[shiftIndex] >= 0) {
                    assignedShiftsByEmployee.get(assignments[shiftIndex]).add(shiftIndex);
                }
            }
            PartialScore total = zero();
            for (int employeeIndex = 0; employeeIndex < problem.employeeCount(); employeeIndex++) {
                sort(assignedShiftsByEmployee.get(employeeIndex));
                contributionByEmployee[employeeIndex] = scoreEmployee(
                        employeeIndex, assignedShiftsByEmployee.get(employeeIndex));
                total = total.plus(contributionByEmployee[employeeIndex]);
            }
            return total;
        }

        PartialScore scoreAfterAdding(
                int[] assignments,
                int[] shiftIndexes,
                int[] employeeIndexes,
                PartialScore currentScore) {
            int[] affectedEmployees = Arrays.stream(employeeIndexes).distinct().sorted().toArray();
            PartialScore result = currentScore;
            for (int employeeIndex : affectedEmployees) {
                result = result.minus(contributionByEmployee[employeeIndex]);
                List<Integer> candidateShifts = new ArrayList<>(assignedShiftsByEmployee.get(employeeIndex));
                for (int index = 0; index < employeeIndexes.length; index++) {
                    if (employeeIndexes[index] == employeeIndex) {
                        candidateShifts.add(shiftIndexes[index]);
                    }
                }
                sort(candidateShifts);
                result = result.plus(scoreEmployee(employeeIndex, candidateShifts));
            }
            return result;
        }

        void commit(Placement placement) {
            int[] affectedEmployees = Arrays.stream(placement.employeeIndexes()).distinct().sorted().toArray();
            for (int index = 0; index < placement.employeeIndexes().length; index++) {
                assignedShiftsByEmployee.get(placement.employeeIndexes()[index])
                        .add(placement.shiftIndexes()[index]);
            }
            for (int employeeIndex : affectedEmployees) {
                sort(assignedShiftsByEmployee.get(employeeIndex));
                contributionByEmployee[employeeIndex] = scoreEmployee(
                        employeeIndex, assignedShiftsByEmployee.get(employeeIndex));
            }
        }

        private PartialScore scoreEmployee(int employeeIndex, List<Integer> shifts) {
            long hardPenalty = 0L;
            long soft0Penalty = 0L; // undesired
            long soft1Penalty = 0L; // fairness
            long soft2Reward = 0L; // desired
            PlanningProblem.EmployeeData employee = problem.employees().get(employeeIndex);

            Map<LocalDate, Integer> nightByLogicalDate = new HashMap<>();
            Map<YearMonth, Integer> nightByMonth = new HashMap<>();
            Map<String, Long> nonNightCountByType = new HashMap<>();
            long nightBurden = 0L;
            long holidayBurden = 0L;

            for (int shiftIndex : shifts) {
                PlanningProblem.ShiftData shift = problem.shifts().get(shiftIndex);
                if (!employee.skillSet().contains(shift.requiredSkill())) {
                    hardPenalty++;
                }
                if (isNight(shift)) {
                    nightByLogicalDate.merge(shift.logicalDate(), 1, Math::addExact);
                    nightByMonth.merge(YearMonth.from(shift.start()), 1, Math::addExact);
                    nightBurden = Math.addExact(nightBurden, shift.nightBurdenScore());
                } else {
                    nonNightCountByType.merge(normalize(shift.shiftCode()), 1L, Math::addExact);
                }
                if (shift.holidayBurdenScore() > 0) {
                    holidayBurden = Math.addExact(holidayBurden, shift.holidayBurdenScore());
                }
                if (!shift.explicitlyPinned()
                        && matchesAvailability(shift, undesiredDates.get(employeeIndex))) {
                    // soft[0] = undesired
                    soft0Penalty = Math.addExact(soft0Penalty,
                            Math.multiplyExact(durationMinutes(shift), employee.offRequestPenaltyWeight()));
                }
                if (desiredDates.get(employeeIndex).contains(shift.start().toLocalDate())) {
                    // soft[2] = desired
                    soft2Reward = Math.addExact(soft2Reward, durationMinutes(shift));
                }
            }

            for (int left = 0; left < shifts.size(); left++) {
                PlanningProblem.ShiftData first = problem.shifts().get(shifts.get(left));
                for (int right = left + 1; right < shifts.size(); right++) {
                    PlanningProblem.ShiftData second = problem.shifts().get(shifts.get(right));
                    if (first.start().isBefore(second.end()) && second.start().isBefore(first.end())) {
                        hardPenalty = Math.addExact(hardPenalty, overlapMinutes(first, second));
                    } else {
                        long rest = breakMinutes(first, second);
                        if (rest >= 0L && rest < MINIMUM_REST_MINUTES) {
                            hardPenalty = Math.addExact(hardPenalty, MINIMUM_REST_MINUTES - rest);
                        }
                    }
                    if (first.start().toLocalDate().equals(second.start().toLocalDate())) {
                        hardPenalty++;
                    }
                }
            }

            for (var entry : nightByLogicalDate.entrySet()) {
                long combinations = entry.getValue();
                for (int offset = 1; offset <= 3; offset++) {
                    combinations = Math.multiplyExact(
                            combinations,
                            nightByLogicalDate.getOrDefault(entry.getKey().plusDays(offset), 0));
                }
                hardPenalty = Math.addExact(hardPenalty, combinations);
            }
            for (int count : nightByMonth.values()) {
                if (count > MAX_MONTHLY_NIGHTS) {
                    hardPenalty = Math.addExact(hardPenalty, count - MAX_MONTHLY_NIGHTS);
                }
            }

            for (int shiftIndex : shifts) {
                PlanningProblem.ShiftData night = problem.shifts().get(shiftIndex);
                if (!isNight(night)) {
                    continue;
                }
                if (nightByLogicalDate.getOrDefault(night.logicalDate().minusDays(1), 0) > 0
                        && nightByLogicalDate.getOrDefault(night.logicalDate().plusDays(1), 0) == 0) {
                    LocalDateTime nextStart = nextStartAtOrAfter(shifts, night.end());
                    if (nextStart != null
                            && minutesBetween(night.end(), nextStart) < POST_NIGHT_RECOVERY_MINUTES) {
                        hardPenalty++;
                    }
                }
                LocalDateTime nextDayStart = nextDayStartAfter(shifts, night.end());
                if (nextDayStart != null) {
                    long rest = minutesBetween(night.end(), nextDayStart);
                    if (rest < NIGHT_TO_DAY_REST_MINUTES) {
                        // Night→Day 32h 미만은 hard (NOD 정책)
                        hardPenalty = Math.addExact(hardPenalty, NIGHT_TO_DAY_REST_MINUTES - rest);
                    }
                }
            }

            // soft[1] = fairness
            soft1Penalty = Math.addExact(soft1Penalty, Math.multiplyExact(nightBurden, nightBurden));
            soft1Penalty = Math.addExact(soft1Penalty, Math.multiplyExact(holidayBurden, holidayBurden));
            for (var entry : nonNightCountByType.entrySet()) {
                long weight = RosterIndex.SHIFT_TYPE_EVENING.equals(entry.getKey()) ? 5L : 1L;
                soft1Penalty = Math.addExact(soft1Penalty,
                        Math.multiplyExact(weight, Math.multiplyExact(entry.getValue(), entry.getValue())));
            }
            return new PartialScore(
                    Math.negateExact(hardPenalty),
                    Math.negateExact(soft0Penalty),
                    Math.negateExact(soft1Penalty),
                    soft2Reward,
                    0L);
        }

        private void sort(List<Integer> shifts) {
            shifts.sort(Comparator.comparing(index -> problem.shifts().get(index).start()));
        }

        private static PartialScore zero() {
            return new PartialScore(0L, 0L, 0L, 0L, 0L);
        }

        private LocalDateTime nextStartAtOrAfter(List<Integer> shifts, LocalDateTime end) {
            LocalDateTime result = null;
            for (int shiftIndex : shifts) {
                LocalDateTime start = problem.shifts().get(shiftIndex).start();
                if (!start.isBefore(end) && (result == null || start.isBefore(result))) {
                    result = start;
                }
            }
            return result;
        }

        private LocalDateTime nextDayStartAfter(List<Integer> shifts, LocalDateTime end) {
            LocalDateTime result = null;
            for (int shiftIndex : shifts) {
                PlanningProblem.ShiftData candidate = problem.shifts().get(shiftIndex);
                if (RosterIndex.SHIFT_TYPE_DAY.equals(normalize(candidate.shiftCode()))
                        && candidate.start().isAfter(end)
                        && (result == null || candidate.start().isBefore(result))) {
                    result = candidate.start();
                }
            }
            return result;
        }

        private static List<Set<LocalDate>> availabilityDates(
                PlanningProblem problem, PlanningProblem.AvailabilityKind kind) {
            List<Set<LocalDate>> result = new ArrayList<>(problem.employeeCount());
            for (int employeeIndex = 0; employeeIndex < problem.employeeCount(); employeeIndex++) {
                result.add(new HashSet<>());
            }
            for (PlanningProblem.AvailabilityData availability : problem.availabilities()) {
                if (availability.kind() == kind) {
                    result.get(availability.employeeIndex()).add(availability.date());
                }
            }
            return result;
        }

        private static boolean matchesAvailability(
                PlanningProblem.ShiftData shift, Set<LocalDate> dates) {
            for (LocalDate date : dates) {
                if (ShiftDateMatcher.matchesActualOrLogicalDate(
                        shift.start(), shift.end(), shift.shiftCode(), date)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean isNight(PlanningProblem.ShiftData shift) {
            return RosterIndex.SHIFT_TYPE_NIGHT.equals(normalize(shift.shiftCode()));
        }

        private static long durationMinutes(PlanningProblem.ShiftData shift) {
            return Duration.between(shift.start(), shift.end()).toMinutes();
        }

        private static long overlapMinutes(
                PlanningProblem.ShiftData first, PlanningProblem.ShiftData second) {
            LocalDateTime start = first.start().isAfter(second.start()) ? first.start() : second.start();
            LocalDateTime end = first.end().isBefore(second.end()) ? first.end() : second.end();
            return Duration.between(start, end).toMinutes();
        }

        private static long breakMinutes(
                PlanningProblem.ShiftData first, PlanningProblem.ShiftData second) {
            if (!first.end().isAfter(second.start())) {
                return Duration.between(first.end(), second.start()).toMinutes();
            }
            if (!second.end().isAfter(first.start())) {
                return Duration.between(second.end(), first.start()).toMinutes();
            }
            return -1L;
        }

        private static long minutesBetween(LocalDateTime from, LocalDateTime to) {
            return Duration.between(from, to).toMinutes();
        }
    }

    private static final class RelationGraph {
        private final List<int[]> groups;

        private RelationGraph(List<int[]> groups) {
            this.groups = groups;
        }

        static RelationGraph create(PlanningProblem problem) {
            int employeeCount = problem.employeeCount();
            int[] parent = new int[employeeCount];
            int[] directedPreceptor = new int[employeeCount];
            Arrays.fill(directedPreceptor, -1);
            for (int employeeIndex = 0; employeeIndex < employeeCount; employeeIndex++) {
                parent[employeeIndex] = employeeIndex;
            }
            for (int employeeIndex = 0; employeeIndex < employeeCount; employeeIndex++) {
                String preceptorId = problem.employees().get(employeeIndex).preceptorExternalId();
                if (preceptorId == null) {
                    continue;
                }
                Integer preceptorIndex = problem.employeeIndexByExternalId().get(preceptorId);
                if (preceptorIndex == null) {
                    throw new RelationGraphException(
                            InitialSolutionFailureCode.MISSING_PRECEPTOR_REFERENCE,
                            List.of(employeeIndex), "존재하지 않는 preceptor ID를 참조합니다.");
                }
                directedPreceptor[employeeIndex] = preceptorIndex;
                union(parent, employeeIndex, preceptorIndex);
            }

            byte[] colors = new byte[employeeCount];
            for (int employeeIndex = 0; employeeIndex < employeeCount; employeeIndex++) {
                detectCycle(employeeIndex, directedPreceptor, colors, new ArrayList<>());
            }

            Map<Integer, List<Integer>> mutableGroups = new LinkedHashMap<>();
            for (int employeeIndex = 0; employeeIndex < employeeCount; employeeIndex++) {
                mutableGroups.computeIfAbsent(find(parent, employeeIndex), ignored -> new ArrayList<>())
                        .add(employeeIndex);
            }
            List<int[]> groups = mutableGroups.values().stream()
                    .map(values -> values.stream().mapToInt(Integer::intValue).toArray())
                    .sorted(Comparator.comparingInt(values -> values[0]))
                    .toList();
            return new RelationGraph(groups);
        }

        List<int[]> groups() {
            return groups;
        }

        private static void detectCycle(
                int employeeIndex, int[] directed, byte[] colors, List<Integer> path) {
            if (colors[employeeIndex] == 2) {
                return;
            }
            if (colors[employeeIndex] == 1) {
                path.add(employeeIndex);
                throw new RelationGraphException(
                        InitialSolutionFailureCode.CYCLIC_PRECEPTOR_RELATION,
                        List.copyOf(path), "preceptor relation cycle이 존재합니다.");
            }
            colors[employeeIndex] = 1;
            path.add(employeeIndex);
            int next = directed[employeeIndex];
            if (next >= 0) {
                detectCycle(next, directed, colors, path);
            }
            path.removeLast();
            colors[employeeIndex] = 2;
        }

        private static int find(int[] parent, int value) {
            int root = value;
            while (parent[root] != root) {
                root = parent[root];
            }
            while (parent[value] != value) {
                int next = parent[value];
                parent[value] = root;
                value = next;
            }
            return root;
        }

        private static void union(int[] parent, int left, int right) {
            int leftRoot = find(parent, left);
            int rightRoot = find(parent, right);
            if (leftRoot != rightRoot) {
                parent[Math.max(leftRoot, rightRoot)] = Math.min(leftRoot, rightRoot);
            }
        }
    }

    private static final class RelationGraphException extends RuntimeException {
        private final InitialSolutionFailureCode code;
        private final List<Integer> employeeIndexes;

        private RelationGraphException(
                InitialSolutionFailureCode code, List<Integer> employeeIndexes, String message) {
            super(message);
            this.code = code;
            this.employeeIndexes = employeeIndexes;
        }
    }
}
