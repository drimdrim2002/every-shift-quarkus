package org.acme.solver.score;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterSolution;

/**
 * 직원·실제 시작일·논리일·shift type·실제 시작 월별 전체 점수 조회 index입니다.
 */
public final class RosterIndex {

    public static final String SHIFT_TYPE_DAY = "D";
    public static final String SHIFT_TYPE_EVENING = "E";
    public static final String SHIFT_TYPE_NIGHT = "N";
    public static final String SHIFT_TYPE_UNKNOWN = "UNKNOWN";

    private final List<List<Integer>> byEmployee;
    private final List<Map<LocalDate, List<Integer>>> byEmployeeActualDate;
    private final List<Map<LocalDate, List<Integer>>> byEmployeeLogicalDate;
    private final List<Map<String, List<Integer>>> byEmployeeShiftType;
    private final List<Map<YearMonth, List<Integer>>> byEmployeeActualMonth;

    RosterIndex(PlanningProblem problem, RosterSolution solution) {
        int employeeCount = problem.employeeCount();
        List<List<Integer>> mutableByEmployee = newBuckets(employeeCount);
        List<Map<LocalDate, List<Integer>>> mutableActualDates = newMaps(employeeCount);
        List<Map<LocalDate, List<Integer>>> mutableLogicalDates = newMaps(employeeCount);
        List<Map<String, List<Integer>>> mutableShiftTypes = newMaps(employeeCount);
        List<Map<YearMonth, List<Integer>>> mutableMonths = newMaps(employeeCount);

        for (int shiftIndex = 0; shiftIndex < problem.shiftCount(); shiftIndex++) {
            int employeeIndex = solution.employeeIndex(shiftIndex);
            PlanningProblem.ShiftData shift = problem.shifts().get(shiftIndex);
            mutableByEmployee.get(employeeIndex).add(shiftIndex);
            add(mutableActualDates.get(employeeIndex), shift.start().toLocalDate(), shiftIndex);
            add(mutableLogicalDates.get(employeeIndex), shift.logicalDate(), shiftIndex);
            add(mutableShiftTypes.get(employeeIndex), normalizeShiftType(shift.shiftCode()), shiftIndex);
            add(mutableMonths.get(employeeIndex), YearMonth.from(shift.start()), shiftIndex);
        }

        for (List<Integer> employeeShifts : mutableByEmployee) {
            employeeShifts.sort((left, right) -> {
                int byStart = problem.shifts().get(left).start().compareTo(problem.shifts().get(right).start());
                return byStart != 0 ? byStart : Integer.compare(left, right);
            });
        }

        this.byEmployee = freezeBuckets(mutableByEmployee);
        this.byEmployeeActualDate = freezeMaps(mutableActualDates);
        this.byEmployeeLogicalDate = freezeMaps(mutableLogicalDates);
        this.byEmployeeShiftType = freezeMaps(mutableShiftTypes);
        this.byEmployeeActualMonth = freezeMaps(mutableMonths);
    }

    public List<Integer> shiftsByEmployee(int employeeIndex) {
        return byEmployee.get(employeeIndex);
    }

    public Map<LocalDate, List<Integer>> shiftsByActualDate(int employeeIndex) {
        return byEmployeeActualDate.get(employeeIndex);
    }

    public Map<LocalDate, List<Integer>> shiftsByLogicalDate(int employeeIndex) {
        return byEmployeeLogicalDate.get(employeeIndex);
    }

    public Map<String, List<Integer>> shiftsByType(int employeeIndex) {
        return byEmployeeShiftType.get(employeeIndex);
    }

    public Map<YearMonth, List<Integer>> shiftsByActualMonth(int employeeIndex) {
        return byEmployeeActualMonth.get(employeeIndex);
    }

    public List<Integer> shiftsByActualDate(int employeeIndex, LocalDate date) {
        return shiftsByActualDate(employeeIndex).getOrDefault(date, List.of());
    }

    public List<Integer> shiftsByLogicalDate(int employeeIndex, LocalDate date) {
        return shiftsByLogicalDate(employeeIndex).getOrDefault(date, List.of());
    }

    public static String normalizeShiftType(String shiftCode) {
        if (shiftCode == null) {
            return SHIFT_TYPE_UNKNOWN;
        }
        String normalized = shiftCode.trim().toUpperCase(Locale.ROOT);
        return normalized.isEmpty() ? SHIFT_TYPE_UNKNOWN : normalized;
    }

    private static <K> void add(Map<K, List<Integer>> map, K key, int shiftIndex) {
        map.computeIfAbsent(key, ignored -> new ArrayList<>()).add(shiftIndex);
    }

    private static List<List<Integer>> newBuckets(int size) {
        List<List<Integer>> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            result.add(new ArrayList<>());
        }
        return result;
    }

    private static <K> List<Map<K, List<Integer>>> newMaps(int size) {
        List<Map<K, List<Integer>>> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            result.add(new LinkedHashMap<>());
        }
        return result;
    }

    private static List<List<Integer>> freezeBuckets(List<List<Integer>> source) {
        return source.stream().map(List::copyOf).toList();
    }

    private static <K> List<Map<K, List<Integer>>> freezeMaps(List<Map<K, List<Integer>>> source) {
        List<Map<K, List<Integer>>> result = new ArrayList<>(source.size());
        for (Map<K, List<Integer>> sourceMap : source) {
            Map<K, List<Integer>> target = new LinkedHashMap<>();
            sourceMap.forEach((key, value) -> target.put(key, List.copyOf(value)));
            result.add(Collections.unmodifiableMap(target));
        }
        return List.copyOf(result);
    }
}
