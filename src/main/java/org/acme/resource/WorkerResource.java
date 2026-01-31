package org.acme.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.xml.bind.ValidationException;
import org.acme.api.dto.PlanningRequest;
import org.acme.model.Employee;
import org.acme.model.EmployeeSchedule;
import org.acme.model.ExecutionStatus;
import org.acme.model.Shift;
import org.acme.service.JobExecutionService;
import org.acme.solver.SolverRunner;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Worker Resource
 * Cloud Tasks에서 호출되는 solver 실행 엔드포인트
 */
@Path("/worker")
public class WorkerResource {

    private static final Logger log = LoggerFactory.getLogger(WorkerResource.class);

    @Inject
    SolverRunner solverRunner;

    @Inject
    JobExecutionService jobExecutionService;

    @POST
    @Path("/process")
    @Consumes(MediaType.APPLICATION_JSON)
    public void processEngineTask(
            @HeaderParam("X-Execution-Id") String executionId,
            PlanningRequest requestDto) throws Exception {

        log.info("Job started: executionId={}, organization={}",
                executionId, requestDto.organization().name());

        // executionId가 없는 경우 (이전 버전 호환성)
        boolean isNewExecution = executionId != null && !executionId.isEmpty();

        if (isNewExecution) {
            // 상태 업데이트: RUNNING
            jobExecutionService.updateStatus(executionId, ExecutionStatus.RUNNING);
        }

        try {
            // Solver 실행
            EmployeeSchedule bestSolution = solverRunner.solve(requestDto);

            HardSoftScore score = bestSolution.getScore();
            log.info("Score: {}", score);

            validateSolution(requestDto, bestSolution);

            printSchedule(bestSolution);

            // 결과 저장
            if (isNewExecution) {
                jobExecutionService.saveResult(executionId, bestSolution);
            }

            log.info("Job completed: executionId={}", executionId);

        } catch (Exception e) {
            log.error("Job failed: executionId={}", executionId, e);
            if (isNewExecution) {
                jobExecutionService.saveError(executionId, e.getMessage());
            }
            throw e;
        }
    }

    private static void printSchedule(EmployeeSchedule schedule) {
        log.info("\n--- Schedule ---");
        Map<LocalDate, List<Shift>> shiftsByDate = schedule.getShiftList().stream()
                .collect(Collectors.groupingBy(shift -> shift.getStart().toLocalDate()));

        shiftsByDate.keySet().stream().sorted().forEach(date -> {
            log.info("Date: {}", date);
            for (Shift shift : shiftsByDate.get(date)) {
                log.info("  {} - {} ({}): {}",
                        shift.getStart().toLocalTime(),
                        shift.getEnd().toLocalTime(),
                        shift.getLocation(),
                        shift.getEmployee() == null ? "UNASSIGNED" : shift.getEmployee().getName());
            }
        });
    }

    private static void validateSolution(PlanningRequest request, EmployeeSchedule schedule) throws ValidationException {
        HashMap<String, String> shiftCodeMap = new HashMap<>();
        for (PlanningRequest.ShiftInfo shift : request.organization().shifts()) {
            shiftCodeMap.put(shift.id(), shift.code());
        }

        // 직원별 근무일 그룹화
        TreeMap<String, TreeMap<LocalDate, List<Shift>>> scheduleByEmployee = new TreeMap<>();
        for (Shift shift : schedule.getShiftList()) {
            Employee employee = shift.getEmployee();

            LocalDateTime start = shift.getStart();
            LocalDate localDate = start.toLocalDate();

            String employeeName = employee.getName();
            scheduleByEmployee.putIfAbsent(employeeName, new TreeMap<>());
            scheduleByEmployee.get(employeeName).putIfAbsent(localDate, new ArrayList<>());
            scheduleByEmployee.get(employeeName).get(localDate).add(shift);
        }

        for (String employeeName : scheduleByEmployee.keySet()) {
            log.info("# Employee: {} ", employeeName);

            for (LocalDate localDate : scheduleByEmployee.get(employeeName).keySet()) {

                if (scheduleByEmployee.get(employeeName).get(localDate).size() > 1) {
                    String message = employeeName + " has multiple shifts in " + localDate;
                    throw new ValidationException(message);
                }

                Shift first = scheduleByEmployee.get(employeeName).get(localDate).getFirst();

                String shiftCode = shiftCodeMap.get(first.getSupabaseId());
                log.info("## localDate: {}, shift: {} ", localDate, shiftCode);
            }
        }
    }
}
