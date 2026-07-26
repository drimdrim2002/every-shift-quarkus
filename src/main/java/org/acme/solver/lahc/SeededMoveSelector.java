package org.acme.solver.lahc;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;

import org.acme.solver.core.PlanningProblem;
import org.acme.solver.move.Move;
import org.acme.solver.move.PreceptorRelationIndex;
import org.acme.solver.move.ReassignMove;
import org.acme.solver.move.RelationGroupExchangeMove;
import org.acme.solver.move.SearchState;
import org.acme.solver.move.SwapMove;

/** 안정 index와 단일 seeded RNG만 사용하는 기본 이웃 선택기입니다. */
public final class SeededMoveSelector implements LahcMoveSelector {

    private static final int RANDOM_ATTEMPTS = 64;

    @Override
    public Optional<Move> select(
            PlanningProblem problem, SearchState state, SplittableRandom random) {
        int[] mutableShifts = problem.mutableShiftIndexes();
        if (mutableShifts.length == 0) {
            return Optional.empty();
        }
        PreceptorRelationIndex relations = new PreceptorRelationIndex(problem);

        for (int attempt = 0; attempt < RANDOM_ATTEMPTS; attempt++) {
            int moveType = random.nextInt(10);
            Optional<Move> selected = switch (moveType) {
                case 0, 1, 2, 3, 4, 5 -> randomReassign(
                        problem, state, random, mutableShifts, relations);
                case 6, 7, 8 -> randomSwap(problem, state, random, mutableShifts, relations);
                default -> randomRelationExchange(problem, state, random, relations);
            };
            if (selected.isPresent()) {
                return selected;
            }
        }

        Optional<Move> fallback = firstReassign(problem, state, mutableShifts, relations);
        if (fallback.isPresent()) {
            return fallback;
        }
        fallback = firstSwap(problem, state, mutableShifts, relations);
        return fallback.isPresent() ? fallback : firstRelationExchange(problem, state, relations);
    }

    private static Optional<Move> randomReassign(
            PlanningProblem problem,
            SearchState state,
            SplittableRandom random,
            int[] mutableShifts,
            PreceptorRelationIndex relations) {
        int shiftIndex = mutableShifts[random.nextInt(mutableShifts.length)];
        int oldEmployee = state.employeeIndex(shiftIndex);
        int newEmployee = random.nextInt(problem.employeeCount());
        if (oldEmployee == newEmployee
                || !isSingleton(relations, oldEmployee)
                || !isSingleton(relations, newEmployee)
                || !canWorkShiftCode(problem, newEmployee, shiftIndex)) {
            return Optional.empty();
        }
        return Optional.of(ReassignMove.create(problem, state, shiftIndex, newEmployee));
    }

    private static Optional<Move> randomSwap(
            PlanningProblem problem,
            SearchState state,
            SplittableRandom random,
            int[] mutableShifts,
            PreceptorRelationIndex relations) {
        if (mutableShifts.length < 2) {
            return Optional.empty();
        }
        int first = mutableShifts[random.nextInt(mutableShifts.length)];
        int second = mutableShifts[random.nextInt(mutableShifts.length)];
        if (first == second) {
            return Optional.empty();
        }
        int firstEmployee = state.employeeIndex(first);
        int secondEmployee = state.employeeIndex(second);
        if (firstEmployee == secondEmployee
                || !isSingleton(relations, firstEmployee)
                || !isSingleton(relations, secondEmployee)
                || !canWorkShiftCode(problem, secondEmployee, first)
                || !canWorkShiftCode(problem, firstEmployee, second)) {
            return Optional.empty();
        }
        return Optional.of(SwapMove.create(problem, state, first, second));
    }

    private static Optional<Move> randomRelationExchange(
            PlanningProblem problem,
            SearchState state,
            SplittableRandom random,
            PreceptorRelationIndex relations) {
        List<RelationSource> sources = relationSources(problem, state, relations);
        List<Slot> slots = mutableSlots(problem);
        if (sources.isEmpty() || slots.size() < 2) {
            return Optional.empty();
        }
        int sourceStart = random.nextInt(sources.size());
        int targetStart = random.nextInt(slots.size());
        for (int sourceOffset = 0; sourceOffset < sources.size(); sourceOffset++) {
            RelationSource source = sources.get((sourceStart + sourceOffset) % sources.size());
            for (int targetOffset = 0; targetOffset < slots.size(); targetOffset++) {
                Slot target = slots.get((targetStart + targetOffset) % slots.size());
                Optional<List<Integer>> targetShifts = compatibleTargetShifts(
                        problem, state, relations, source, target, random);
                if (targetShifts.isPresent()) {
                    return Optional.of(RelationGroupExchangeMove.create(
                            problem, state, source.shiftIndexes(), targetShifts.orElseThrow()));
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Move> firstReassign(
            PlanningProblem problem,
            SearchState state,
            int[] mutableShifts,
            PreceptorRelationIndex relations) {
        for (int shiftIndex : mutableShifts) {
            int oldEmployee = state.employeeIndex(shiftIndex);
            if (!isSingleton(relations, oldEmployee)) {
                continue;
            }
            for (int newEmployee = 0; newEmployee < problem.employeeCount(); newEmployee++) {
                if (newEmployee != oldEmployee
                        && isSingleton(relations, newEmployee)
                        && canWorkShiftCode(problem, newEmployee, shiftIndex)) {
                    return Optional.of(ReassignMove.create(problem, state, shiftIndex, newEmployee));
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Move> firstSwap(
            PlanningProblem problem,
            SearchState state,
            int[] mutableShifts,
            PreceptorRelationIndex relations) {
        for (int left = 0; left < mutableShifts.length; left++) {
            int first = mutableShifts[left];
            int firstEmployee = state.employeeIndex(first);
            if (!isSingleton(relations, firstEmployee)) {
                continue;
            }
            for (int right = left + 1; right < mutableShifts.length; right++) {
                int second = mutableShifts[right];
                int secondEmployee = state.employeeIndex(second);
                if (firstEmployee != secondEmployee
                        && isSingleton(relations, secondEmployee)
                        && canWorkShiftCode(problem, secondEmployee, first)
                        && canWorkShiftCode(problem, firstEmployee, second)) {
                    return Optional.of(SwapMove.create(problem, state, first, second));
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Move> firstRelationExchange(
            PlanningProblem problem,
            SearchState state,
            PreceptorRelationIndex relations) {
        for (RelationSource source : relationSources(problem, state, relations)) {
            for (Slot target : mutableSlots(problem)) {
                Optional<List<Integer>> targetShifts = compatibleTargetShifts(
                        problem, state, relations, source, target, null);
                if (targetShifts.isPresent()) {
                    return Optional.of(RelationGroupExchangeMove.create(
                            problem, state, source.shiftIndexes(), targetShifts.orElseThrow()));
                }
            }
        }
        return Optional.empty();
    }

    private static List<RelationSource> relationSources(
            PlanningProblem problem,
            SearchState state,
            PreceptorRelationIndex relations) {
        List<RelationSource> result = new ArrayList<>();
        for (var groupEntry : relations.groups().entrySet()) {
            List<Integer> groupEmployees = groupEntry.getValue();
            if (groupEmployees.size() < 2) {
                continue;
            }
            Map<SlotKey, List<Integer>> bySlot = new LinkedHashMap<>();
            for (int shiftIndex : problem.mutableShiftIndexes()) {
                if (relations.groupId(state.employeeIndex(shiftIndex)) == groupEntry.getKey()) {
                    SlotKey slot = SlotKey.of(problem.shifts().get(shiftIndex));
                    bySlot.computeIfAbsent(slot, ignored -> new ArrayList<>()).add(shiftIndex);
                }
            }
            for (var slotEntry : bySlot.entrySet()) {
                List<Integer> shifts = slotEntry.getValue();
                if (shifts.size() != groupEmployees.size()) {
                    continue;
                }
                List<Integer> assignedEmployees = shifts.stream()
                        .map(state::employeeIndex)
                        .distinct()
                        .sorted()
                        .toList();
                if (assignedEmployees.equals(groupEmployees)) {
                    result.add(new RelationSource(
                            groupEmployees, slotEntry.getKey(), List.copyOf(shifts)));
                }
            }
        }
        return List.copyOf(result);
    }

    private static List<Slot> mutableSlots(PlanningProblem problem) {
        Map<SlotKey, List<Integer>> bySlot = new LinkedHashMap<>();
        for (int shiftIndex : problem.mutableShiftIndexes()) {
            SlotKey key = SlotKey.of(problem.shifts().get(shiftIndex));
            bySlot.computeIfAbsent(key, ignored -> new ArrayList<>()).add(shiftIndex);
        }
        return bySlot.entrySet().stream()
                .map(entry -> new Slot(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
    }

    private static Optional<List<Integer>> compatibleTargetShifts(
            PlanningProblem problem,
            SearchState state,
            PreceptorRelationIndex relations,
            RelationSource source,
            Slot target,
            SplittableRandom random) {
        if (source.slot().equals(target.key())) {
            return Optional.empty();
        }
        // 서로 다른 날짜의 안전한 이동은 양쪽 직원의 기존 날짜 배정까지 포함하는 더 긴 chain이 필요합니다.
        if (!source.slot().date().equals(target.key().date())) {
            return Optional.empty();
        }
        for (int employeeIndex : source.employees()) {
            if (!canWorkShiftCode(problem, employeeIndex, target.shiftIndexes().getFirst())) {
                return Optional.empty();
            }
        }

        List<Integer> compatible = new ArrayList<>();
        List<Integer> seenEmployees = new ArrayList<>();
        for (int shiftIndex : target.shiftIndexes()) {
            int employeeIndex = state.employeeIndex(shiftIndex);
            if (relations.employeesFor(employeeIndex).size() == 1
                    && !seenEmployees.contains(employeeIndex)
                    && canWorkShiftCode(problem, employeeIndex, source.shiftIndexes().getFirst())) {
                compatible.add(shiftIndex);
                seenEmployees.add(employeeIndex);
            }
        }
        if (compatible.size() < source.employees().size()) {
            return Optional.empty();
        }

        int start = random == null ? 0 : random.nextInt(compatible.size());
        List<Integer> selected = new ArrayList<>(source.employees().size());
        for (int offset = 0; selected.size() < source.employees().size(); offset++) {
            selected.add(compatible.get((start + offset) % compatible.size()));
        }
        return Optional.of(List.copyOf(selected));
    }

    private static boolean isSingleton(PreceptorRelationIndex relations, int employeeIndex) {
        return relations.employeesFor(employeeIndex).size() == 1;
    }

    private static boolean canWorkShiftCode(
            PlanningProblem problem, int employeeIndex, int shiftIndex) {
        String shiftCode = normalize(problem.shifts().get(shiftIndex).shiftCode());
        return problem.employees().get(employeeIndex).availableShiftCodes().stream()
                .map(SeededMoveSelector::normalize)
                .anyMatch(shiftCode::equals);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private record SlotKey(LocalDate date, String code) {
        static SlotKey of(PlanningProblem.ShiftData shift) {
            return new SlotKey(shift.start().toLocalDate(), normalize(shift.shiftCode()));
        }
    }

    private record Slot(SlotKey key, List<Integer> shiftIndexes) {
    }

    private record RelationSource(
            List<Integer> employees,
            SlotKey slot,
            List<Integer> shiftIndexes) {
    }
}
