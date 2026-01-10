package org.acme.resource;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import org.acme.api.dto.PlanningRequest;
import org.acme.solver.algorithm.EmployeeSchedulingConstraintProvider;
import org.acme.model.EmployeeSchedule;
import org.acme.model.Shift;
import org.acme.util.DtoConverter;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;
import org.optaplanner.core.api.solver.Solver;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.core.config.solver.SolverConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Path("/worker")
public class WorkerResource {

    private static final Logger log = LoggerFactory.getLogger(WorkerResource.class);

    @POST
    @Path("/process")
    @Consumes(MediaType.APPLICATION_JSON)
    public void processEngineTask(PlanningRequest requestDto) {

        log.info("작업 시작: {}", requestDto.organization().name());

        EmployeeSchedule problem = DtoConverter.toEmployeeSchedule(requestDto);

        SolverFactory<EmployeeSchedule> solverFactory = SolverFactory.create(new SolverConfig()
                .withSolutionClass(EmployeeSchedule.class)
                .withEntityClasses(Shift.class)
                .withConstraintProviderClass(EmployeeSchedulingConstraintProvider.class)
                .withTerminationSpentLimit(java.time.Duration.ofSeconds(10)));


        Solver<EmployeeSchedule> employeeScheduleSolver = solverFactory.buildSolver();

        EmployeeSchedule bestSolution = employeeScheduleSolver.solve(problem);

        HardSoftScore score = bestSolution.getScore();
        log.info("Score: {}", score);
        printSchedule(bestSolution);


        // solverService.solve(requestDto);


        // 날짜별 요구사항 확인 예시
        if (requestDto.requirements() != null) {
            requestDto.requirements().forEach((date, counts) -> {
                log.info("날짜: {}, 요구사항 수: {}", date, counts.size());
            });
        }

        log.info("작업 완료");

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
}
