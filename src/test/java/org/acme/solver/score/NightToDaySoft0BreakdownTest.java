package org.acme.solver.score;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.acme.api.dto.PlanningRequest;
import org.acme.converter.EmployeeScheduleBuilder;
import org.acme.export.dto.ScheduleJsonDto;
import org.acme.export.dto.ScheduleJsonDto.EmployeeShiftsDto;
import org.acme.export.dto.ScheduleJsonDto.ShiftDetailDto;
import org.acme.model.EmployeeSchedule;
import org.acme.solver.adapter.PlanningProblemMapper;
import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.test.JsonLoader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 과거 soft[0]=-480 배정 스냅샷에서 Night→Day rest 위반이 이제 hard로 집계되는지 확정한다.
 */
@Tag("remote-replay")
class NightToDaySoft0BreakdownTest {

    private static final String INPUT_RESOURCE =
            "/json/remote/3e56517c-2682-4ee2-a89f-310a3813b983.json";
    private static final Path KNOWN_EXPORT = Path.of(
            "testdata/remote/3e56517c-2682-4ee2-a89f-310a3813b983/schedule-soft0-minus480.json");
    private static final DateTimeFormatter EXPORT_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final EmployeeScheduleBuilder scheduleBuilder = new EmployeeScheduleBuilder();
    private final PlanningProblemMapper problemMapper = new PlanningProblemMapper();
    private final FullScoreCalculator calculator = new FullScoreCalculator();

    @Test
    void nightToDay_24h_rest_is_hard_minus480_not_soft0() throws IOException {
        assertTrue(Files.isRegularFile(KNOWN_EXPORT), "진단용 export 필요: " + KNOWN_EXPORT);

        PlanningRequest request = objectMapper.readValue(
                JsonLoader.loadAsString(INPUT_RESOURCE), PlanningRequest.class);
        EmployeeSchedule source = scheduleBuilder.build(request);
        PlanningProblem problem = problemMapper.toPlanningProblem(source);

        ScheduleJsonDto export = objectMapper.readValue(KNOWN_EXPORT.toFile(), ScheduleJsonDto.class);
        RosterSolution solution = reconstructSolution(problem, export);
        ScoreCalculationResult result = calculator.calculateWithBreakdown(problem, solution);

        RosterScore nightScore = result.scoreByConstraintId()
                .getOrDefault(ConstraintIds.NIGHT_TO_DAY_REST, RosterScore.of(0, 0, 0, 0, 0));
        assertEquals(-480, nightScore.hardScore(),
                "NIGHT_TO_DAY_REST must contribute to hard only");
        assertEquals(0, nightScore.softScore(0));
        assertEquals(0, nightScore.softScore(1));
        assertEquals(0, nightScore.softScore(2));
        assertEquals(0, nightScore.softScore(3));

        // 이 스냅샷의 soft[0]은 undesired(0) — NOD가 soft에 남지 않음
        long soft0FromNight = result.contributions().stream()
                .filter(c -> c.level() == ScoreLevel.SOFT_0)
                .filter(c -> ConstraintIds.NIGHT_TO_DAY_REST.equals(c.constraintId()))
                .count();
        assertEquals(0, soft0FromNight);

        List<ConstraintContribution> violations = result.contributions(ConstraintIds.NIGHT_TO_DAY_REST);
        assertEquals(1, violations.size());
        ConstraintContribution v = violations.getFirst();
        assertEquals(ScoreLevel.HARD, v.level());
        assertEquals(-480, v.contribution());

        PlanningProblem.EmployeeData employee = problem.employees().get(v.employeeIndexes().getFirst());
        PlanningProblem.ShiftData night = problem.shifts().get(v.shiftIndexes().getFirst());
        PlanningProblem.ShiftData day = problem.shifts().get(v.shiftIndexes().get(1));
        assertEquals("이민아", employee.name());
        assertEquals(1440, ScoreSupport.minutesBetween(night.end(), day.start()));
        assertEquals(LocalDateTime.of(2026, 3, 15, 8, 0), night.end());
        assertEquals(LocalDateTime.of(2026, 3, 16, 8, 0), day.start());

        System.out.printf(
                "NOD hard breakdown: employee=%s restMinutes=1440 hardContribution=%d score=%s%n",
                employee.name(), v.contribution(), result.score());
    }

    private RosterSolution reconstructSolution(PlanningProblem problem, ScheduleJsonDto export) {
        Map<String, Integer> employeeIndexById = problem.employeeIndexByExternalId();
        Map<String, List<Integer>> shiftsByKey = new HashMap<>();
        for (int shiftIndex = 0; shiftIndex < problem.shiftCount(); shiftIndex++) {
            PlanningProblem.ShiftData shift = problem.shifts().get(shiftIndex);
            shiftsByKey.computeIfAbsent(shiftKey(shift.shiftCode(), shift.start(), shift.end()),
                    ignored -> new ArrayList<>()).add(shiftIndex);
        }

        int[] assignments = new int[problem.shiftCount()];
        boolean[] assigned = new boolean[problem.shiftCount()];
        Set<String> usedExportSlots = new HashSet<>();

        for (EmployeeShiftsDto emp : export.getEmployees()) {
            Integer employeeIndex = employeeIndexById.get(emp.getId());
            if (employeeIndex == null) {
                throw new IllegalStateException("export employee not in problem: " + emp.getId());
            }
            if (emp.getShifts() == null) {
                continue;
            }
            for (ShiftDetailDto slot : emp.getShifts()) {
                LocalDateTime start = parseExportTime(slot.getStartTime());
                LocalDateTime end = parseExportTime(slot.getEndTime());
                String key = shiftKey(slot.getCode(), start, end);
                String exportKey = emp.getId() + "|" + key;
                assertFalse(usedExportSlots.contains(exportKey), "duplicate export slot: " + exportKey);
                usedExportSlots.add(exportKey);

                List<Integer> candidates = shiftsByKey.get(key);
                if (candidates == null || candidates.isEmpty()) {
                    throw new IllegalStateException("no problem shift for export slot: " + key
                            + " employee=" + emp.getName());
                }
                int shiftIndex = candidates.removeFirst();
                assignments[shiftIndex] = employeeIndex;
                assigned[shiftIndex] = true;
            }
        }

        for (int i = 0; i < assigned.length; i++) {
            if (!assigned[i]) {
                PlanningProblem.ShiftData s = problem.shifts().get(i);
                throw new IllegalStateException("unassigned problem shift: idx=" + i
                        + " code=" + s.shiftCode() + " start=" + s.start());
            }
        }

        return new RosterSolution(problem.employeeCount(), assignments, RosterScore.of(0, 0, 0, 0, 0));
    }

    private static String shiftKey(String code, LocalDateTime start, LocalDateTime end) {
        return code + "|" + start + "|" + end;
    }

    private static LocalDateTime parseExportTime(String raw) {
        String normalized = raw.contains("T") ? raw.replace('T', ' ') : raw;
        if (normalized.length() > 16) {
            normalized = normalized.substring(0, 16);
        }
        return LocalDateTime.parse(normalized, EXPORT_TIME);
    }
}
