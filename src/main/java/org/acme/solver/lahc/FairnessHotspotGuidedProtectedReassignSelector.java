package org.acme.solver.lahc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.SplittableRandom;

import org.acme.solver.core.PlanningProblem;
import org.acme.solver.move.Move;
import org.acme.solver.move.ReassignMove;
import org.acme.solver.move.SearchState;

/**
 * fairness 제곱 항의 정확한 국소 delta를 정렬 힌트로 사용해 protected reassign 후보를 줄입니다.
 *
 * <p>이 힌트는 soft[2]의 세 fairness 제약만 계산하며 hard/soft[0]/soft[1]의 수락 근거가 아닙니다.
 * 최종 수락은 {@link FairnessRestrictedLocalSearchEngine}의 transaction full-score 검증과
 * 사전식 보호 조건에만 맡깁니다. production selector에는 등록하지 않는 연구 lane입니다.</p>
 */
public final class FairnessHotspotGuidedProtectedReassignSelector implements LahcMoveSelector {

    /** 한 current state에서 full-score로 확인할 최대 후보 수입니다. */
    public static final int DEFAULT_MAX_CANDIDATES_PER_STATE = 256;

    private final int maxCandidatesPerState;
    private final Deque<Candidate> pending = new ArrayDeque<>();
    private int[] assignmentSnapshot;
    private long rankingBuildCount;
    private long rawReassignCandidates;
    private long eligibilityCandidates;
    private long fairnessHintCandidates;
    private long emittedCandidates;

    public FairnessHotspotGuidedProtectedReassignSelector() {
        this(DEFAULT_MAX_CANDIDATES_PER_STATE);
    }

    public FairnessHotspotGuidedProtectedReassignSelector(int maxCandidatesPerState) {
        if (maxCandidatesPerState < 1) {
            throw new IllegalArgumentException("maxCandidatesPerState는 1 이상이어야 합니다.");
        }
        this.maxCandidatesPerState = maxCandidatesPerState;
    }

    @Override
    public Optional<Move> select(PlanningProblem problem, SearchState state, SplittableRandom random) {
        int[] assignments = state.assignments();
        if (!Arrays.equals(assignmentSnapshot, assignments)) {
            rebuild(problem, state, assignments);
        }
        Candidate candidate = pending.pollFirst();
        if (candidate == null) {
            return Optional.empty();
        }
        emittedCandidates++;
        return Optional.of(ReassignMove.create(problem, state, candidate.shiftIndex(), candidate.targetEmployee()));
    }

    public FairnessSelectorMetrics metrics() {
        return new FairnessSelectorMetrics(rankingBuildCount, rawReassignCandidates, eligibilityCandidates,
                fairnessHintCandidates, emittedCandidates);
    }

    private void rebuild(PlanningProblem problem, SearchState state, int[] assignments) {
        pending.clear();
        assignmentSnapshot = Arrays.copyOf(assignments, assignments.length);
        rankingBuildCount++;

        Burden[] burdens = burdens(problem, assignments);
        List<Candidate> ranked = new ArrayList<>();
        for (int shiftIndex : problem.mutableShiftIndexes()) {
            int source = assignments[shiftIndex];
            for (int target = 0; target < problem.employeeCount(); target++) {
                if (source == target) {
                    continue;
                }
                rawReassignCandidates++;
                if (!isEligible(problem, shiftIndex, target)) {
                    continue;
                }
                eligibilityCandidates++;
                long fairnessDelta = fairnessDelta(problem, burdens, shiftIndex, source, target);
                if (fairnessDelta <= 0L) {
                    continue;
                }
                fairnessHintCandidates++;
                ranked.add(new Candidate(shiftIndex, source, target, fairnessDelta,
                        burdens[source].penalty(), burdens[target].penalty()));
            }
        }
        ranked.sort((left, right) -> {
            int comparison = Long.compare(right.fairnessDelta(), left.fairnessDelta());
            if (comparison != 0) {
                return comparison;
            }
            comparison = Long.compare(right.sourcePenalty(), left.sourcePenalty());
            if (comparison != 0) {
                return comparison;
            }
            comparison = Long.compare(left.targetPenalty(), right.targetPenalty());
            if (comparison != 0) {
                return comparison;
            }
            comparison = Integer.compare(left.shiftIndex(), right.shiftIndex());
            return comparison != 0 ? comparison : Integer.compare(left.targetEmployee(), right.targetEmployee());
        });
        for (int index = 0; index < Math.min(maxCandidatesPerState, ranked.size()); index++) {
            pending.addLast(ranked.get(index));
        }
    }

    private static boolean isEligible(PlanningProblem problem, int shiftIndex, int targetEmployee) {
        PlanningProblem.ShiftData shift = problem.shifts().get(shiftIndex);
        PlanningProblem.EmployeeData employee = problem.employees().get(targetEmployee);
        String shiftCode = normalize(shift.shiftCode());
        boolean shiftTypeAllowed = employee.availableShiftCodes().stream()
                .map(FairnessHotspotGuidedProtectedReassignSelector::normalize)
                .anyMatch(shiftCode::equals);
        return shiftTypeAllowed && employee.skillSet().contains(shift.requiredSkill());
    }

    private static Burden[] burdens(PlanningProblem problem, int[] assignments) {
        Burden[] burdens = new Burden[problem.employeeCount()];
        for (int employee = 0; employee < burdens.length; employee++) {
            burdens[employee] = new Burden();
        }
        for (int shiftIndex = 0; shiftIndex < assignments.length; shiftIndex++) {
            add(problem.shifts().get(shiftIndex), burdens[assignments[shiftIndex]], 1);
        }
        return burdens;
    }

    private static long fairnessDelta(
            PlanningProblem problem, Burden[] burdens, int shiftIndex, int source, int target) {
        PlanningProblem.ShiftData shift = problem.shifts().get(shiftIndex);
        Burden sourceAfter = burdens[source].after(shift, -1);
        Burden targetAfter = burdens[target].after(shift, 1);
        return Math.addExact(Math.addExact(burdens[source].penalty(), burdens[target].penalty()),
                -Math.addExact(sourceAfter.penalty(), targetAfter.penalty()));
    }

    private static void add(PlanningProblem.ShiftData shift, Burden burden, int direction) {
        burden.night += direction * (long) shift.nightBurdenScore();
        burden.holiday += direction * (long) shift.holidayBurdenScore();
        String type = normalize(shift.shiftCode());
        if (!"N".equals(type)) {
            if ("E".equals(type)) {
                burden.evening += direction;
            } else {
                burden.day += direction;
            }
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private record Candidate(int shiftIndex, int sourceEmployee, int targetEmployee, long fairnessDelta,
            long sourcePenalty, long targetPenalty) {
    }

    private static final class Burden {
        private long night;
        private long holiday;
        private long day;
        private long evening;

        private Burden after(PlanningProblem.ShiftData shift, int direction) {
            Burden copy = new Burden();
            copy.night = night;
            copy.holiday = holiday;
            copy.day = day;
            copy.evening = evening;
            add(shift, copy, direction);
            return copy;
        }

        private long penalty() {
            return Math.addExact(Math.addExact(square(night), square(holiday)),
                    Math.addExact(square(day), Math.multiplyExact(5L, square(evening))));
        }

        private static long square(long value) {
            return Math.multiplyExact(value, value);
        }
    }
}
