package org.acme.solver.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.acme.api.dto.PlanningRequest;
import org.acme.converter.EmployeeScheduleBuilder;
import org.acme.model.EmployeeSchedule;
import org.acme.model.Shift;
import org.acme.solver.adapter.EmployeeScheduleProjection;
import org.acme.solver.adapter.PlanningProblemMapper;
import org.acme.solver.algorithm.EmployeeSchedulingConstraintProvider;
import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.core.SolveOptions;
import org.acme.solver.core.SolveResult;
import org.acme.solver.lahc.OptaStyleLocalSearchEngine;
import org.acme.solver.move.ChainMove;
import org.acme.solver.move.Move;
import org.acme.solver.move.MoveTransaction;
import org.acme.solver.move.SearchState;
import org.acme.solver.move.SwapMove;
import org.acme.solver.optaplanner.OptaPlannerScoreAdapter;
import org.acme.solver.score.FullScoreCalculator;
import org.acme.solver.score.IncrementalScoreCalculator;
import org.acme.test.JsonLoader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.optaplanner.core.api.score.buildin.bendable.BendableScore;
import org.optaplanner.core.api.solver.SolutionManager;
import org.optaplanner.core.api.solver.Solver;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.core.config.solver.EnvironmentMode;
import org.optaplanner.core.config.solver.SolverConfig;
import org.optaplanner.core.config.solver.termination.TerminationConfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * fairness incumbent에서 상위 목적식을 보존하는 atomic witness를 bounded exhaustive하게 찾는 진단입니다.
 * production selector나 ALNS operator에는 연결하지 않습니다.
 */
@Tag("benchmark")
class FairnessAtomicWitnessDiagnosticTest {

    private static final String DATASET = "fairness.json";
    private static final long DEFAULT_OPTA_EVALUATIONS = 150_000L;
    private static final long DEFAULT_POJO_EVALUATIONS = 100_000L;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final EmployeeScheduleBuilder scheduleBuilder = new EmployeeScheduleBuilder();
    private final PlanningProblemMapper problemMapper = new PlanningProblemMapper();
    private final EmployeeScheduleProjection projection = new EmployeeScheduleProjection();
    private final FullScoreCalculator full = new FullScoreCalculator();
    private final SolutionManager<EmployeeSchedule, BendableScore> solutionManager = SolutionManager.create(
            SolverFactory.<EmployeeSchedule>create(new SolverConfig()
                    .withSolutionClass(EmployeeSchedule.class)
                    .withEntityClasses(Shift.class)
                    .withConstraintProviderClass(EmployeeSchedulingConstraintProvider.class)));

    @Test
    void seed601_602의_fairness_incumbent에서_bounded_atomic_witness를_완전열거한다() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("fairness.atomic.diagnostic.enabled"),
                "명시적 diagnostic 실행에서만 artifact를 생성합니다.");
        List<Long> seeds = csvLongProperty("fairness.atomic.diagnostic.seeds", List.of(601L, 602L));
        long optaEvaluations = Long.getLong("fairness.atomic.diagnostic.opta-evaluations", DEFAULT_OPTA_EVALUATIONS);
        long pojoEvaluations = Long.getLong("fairness.atomic.diagnostic.pojo-evaluations", DEFAULT_POJO_EVALUATIONS);
        Path output = Path.of(System.getProperty("fairness.atomic.diagnostic.output",
                "benchmark-artifacts/phase6/diagnostics/fairness-atomic/" + Instant.now().toString().replace(':', '-')));
        Files.createDirectories(output);

        List<SeedResult> results = new ArrayList<>();
        for (long seed : seeds) {
            results.add(runSeed(seed, optaEvaluations, pojoEvaluations));
        }
        writeArtifacts(output, optaEvaluations, pojoEvaluations, results);

        for (SeedResult result : results) {
            assertEquals(0, result.incumbentScore().hardScore(), result::toString);
            assertEquals(0L, result.rollbackFailures(), result::toString);
            assertEquals(0L, result.scoreMismatches(), result::toString);
        }
    }

    private SeedResult runSeed(long seed, long optaEvaluations, long pojoEvaluations) throws Exception {
        PlanningRequest request = objectMapper.readValue(
                JsonLoader.loadAsString("/json/" + DATASET), PlanningRequest.class);
        PlanningProblem problem = problemMapper.toPlanningProblem(scheduleBuilder.build(request));

        long optaStarted = System.nanoTime();
        EmployeeSchedule optaSchedule = optaSolver(seed, optaEvaluations).solve(scheduleBuilder.build(request));
        long optaElapsedMillis = elapsedMillis(optaStarted);
        RosterSolution optaSolution = projection.toRosterSolution(problem, optaSchedule);
        RosterScore optaFull = full.calculateScore(problem, optaSolution);
        RosterScore optaManager = managerScore(problem, optaSolution);
        assertEquals(optaFull, optaSolution.score(), "Opta assignment의 POJO full score가 adapter score와 달라졌습니다.");
        assertEquals(optaManager, optaSolution.score(), "Opta assignment의 SolutionManager score가 adapter score와 달라졌습니다.");

        long pojoStarted = System.nanoTime();
        SolveResult<RosterSolution> pojoResult = new OptaStyleLocalSearchEngine().solve(problem,
                SolveOptions.builder().maxEvaluations(pojoEvaluations).randomSeed(seed).build(), ignored -> { });
        long pojoElapsedMillis = elapsedMillis(pojoStarted);
        RosterSolution incumbent = Objects.requireNonNull(pojoResult.bestSolution(), "POJO incumbent");
        RosterScore incumbentFull = full.calculateScore(problem, incumbent);
        RosterScore incumbentManager = managerScore(problem, incumbent);
        assertEquals(incumbentFull, incumbent.score(), "POJO incumbent의 full score가 결과 score와 달라졌습니다.");
        assertEquals(incumbentManager, incumbent.score(), "POJO incumbent의 SolutionManager score가 결과 score와 달라졌습니다.");

        AtomicEnumeration enumeration = new AtomicEnumeration(problem, incumbent, full);
        enumeration.enumerateAll();
        return new SeedResult(seed, optaEvaluations, pojoEvaluations, optaElapsedMillis, pojoElapsedMillis,
                optaSolution.score(), incumbent.score(), diffGraph(problem, incumbent, optaSolution),
                enumeration.counts(), enumeration.bestWitness(), enumeration.rollbackFailures,
                enumeration.scoreMismatches, enumeration.elapsedMillis());
    }

    private Solver<EmployeeSchedule> optaSolver(long seed, long evaluationLimit) {
        return SolverFactory.<EmployeeSchedule>create(new SolverConfig()
                .withSolutionClass(EmployeeSchedule.class)
                .withEntityClasses(Shift.class)
                .withConstraintProviderClass(EmployeeSchedulingConstraintProvider.class)
                .withTerminationConfig(new TerminationConfig().withScoreCalculationCountLimit(evaluationLimit))
                .withMoveThreadCount("NONE")
                .withEnvironmentMode(EnvironmentMode.REPRODUCIBLE)
                .withRandomSeed(seed))
                .buildSolver();
    }

    private RosterScore managerScore(PlanningProblem problem, RosterSolution solution) {
        return OptaPlannerScoreAdapter.toRosterScore(
                solutionManager.update(projection.toEmployeeSchedule(problem, solution)));
    }

    private List<DiffEdge> diffGraph(
            PlanningProblem problem, RosterSolution incumbent, RosterSolution optaSolution) {
        List<DiffEdge> edges = new ArrayList<>();
        for (int shiftIndex = 0; shiftIndex < problem.shiftCount(); shiftIndex++) {
            int from = incumbent.employeeIndex(shiftIndex);
            int to = optaSolution.employeeIndex(shiftIndex);
            if (from != to) {
                PlanningProblem.ShiftData shift = problem.shifts().get(shiftIndex);
                edges.add(new DiffEdge(shiftIndex, shift.planningId(), shift.start().toLocalDate(), shift.shiftCode(),
                        problem.employees().get(from).externalId(), problem.employees().get(to).externalId()));
            }
        }
        return List.copyOf(edges);
    }

    private void writeArtifacts(
            Path output, long optaEvaluations, long pojoEvaluations, List<SeedResult> results) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema_version", 1);
        root.put("dataset", DATASET);
        root.put("opta_evaluation_limit", optaEvaluations);
        root.put("pojo_evaluation_limit", pojoEvaluations);
        root.put("seed_partition", "diagnostic-development: 601,602; excluded: 101..110,201/202,301/302");
        root.put("candidate_contract", "transaction apply -> IncrementalScoreCalculator verifyAgainstFull -> rollback");
        root.put("witness_contract", "hard>=incumbent.hard, soft0>=incumbent.soft0, soft1>=incumbent.soft1, soft2>incumbent.soft2; soft3 ignored");
        root.put("complete_bounds", List.of(
                "모든 mutable shift × 모든 다른 employee의 1-reassign",
                "서로 다른 incumbent employee를 가진 모든 mutable shift pair의 2-swap",
                "같은 실제일의 mutable shift 중 incumbent employee가 모두 다른 모든 3-cycle",
                "같은 shift-type의 4일 연속 window 안 mutable shift 중 incumbent employee가 모두 다른 모든 3/4-cycle"));
        root.put("results", results);
        Files.writeString(output.resolve("summary.json"), objectMapper.writeValueAsString(root) + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        StringBuilder report = new StringBuilder("# fairness atomic witness diagnostic\n\n")
                .append("- dataset: `").append(DATASET).append("`\n")
                .append("- Opta evaluation: `").append(optaEvaluations).append("`\n")
                .append("- POJO evaluation: `").append(pojoEvaluations).append("`\n")
                .append("- seed: `").append(results.stream().map(SeedResult::seed).toList()).append("`\n\n")
                .append("각 후보는 transaction 적용 후 full-score 검증을 통과한 경우에만 판정하고 rollback했습니다. ")
                .append("soft[3]은 witness 판정에 사용하지 않았습니다.\n\n")
                .append("| seed | Opta score | incumbent score | diff edge | candidate | witness | rollback/mismatch | elapsed ms |\n")
                .append("|---:|---|---|---:|---:|---|---:|---:|\n");
        for (SeedResult result : results) {
            report.append("| ").append(result.seed()).append(" | ").append(result.optaScore())
                    .append(" | ").append(result.incumbentScore()).append(" | ")
                    .append(result.diffGraph().size()).append(" | ")
                    .append(result.counts().total()).append(" | ")
                    .append(result.witness() == null ? "없음" : "발견")
                    .append(" | ").append(result.rollbackFailures()).append('/').append(result.scoreMismatches())
                    .append(" | ").append(result.enumerationElapsedMillis()).append(" |\n");
            report.append("\n### seed ").append(result.seed()).append("\n\n")
                    .append("- Opta/POJO 재계산: POJO FullScoreCalculator 및 Opta SolutionManager 일치\n")
                    .append("- candidate count: ").append(result.counts()).append("\n")
                    .append("- diff graph edge: ").append(result.diffGraph().size()).append("\n");
            if (result.witness() == null) {
                report.append("- 결론: 명시한 bounded neighborhood 안에 witness 없음\n");
            } else {
                report.append("- 최소 witness: ").append(result.witness()).append("\n");
            }
        }
        Files.writeString(output.resolve("report.md"), report.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static List<Long> csvLongProperty(String name, List<Long> defaults) {
        String raw = System.getProperty(name, defaults.stream().map(String::valueOf).collect(Collectors.joining(",")));
        return Arrays.stream(raw.split(",")).map(String::strip).filter(value -> !value.isEmpty())
                .map(Long::parseLong).toList();
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static final class AtomicEnumeration {

        private final PlanningProblem problem;
        private final RosterSolution incumbent;
        private final FullScoreCalculator full;
        private final SearchState state;
        private final IncrementalScoreCalculator incremental;
        private final Counts.Mutable counts = new Counts.Mutable();
        private final Set<String> distinctCandidateKeys = new LinkedHashSet<>();
        private Witness bestWitness;
        private long rollbackFailures;
        private long scoreMismatches;
        private long startedNanos;

        private AtomicEnumeration(PlanningProblem problem, RosterSolution incumbent, FullScoreCalculator full) {
            this.problem = problem;
            this.incumbent = incumbent;
            this.full = full;
            this.state = new SearchState(problem, incumbent);
            this.incremental = new IncrementalScoreCalculator(problem, incumbent, full);
        }

        void enumerateAll() {
            startedNanos = System.nanoTime();
            enumerateReassign();
            enumerateSwap();
            enumerateSameDayThreeCycles();
            enumerateShiftTypeWindowCycles();
        }

        long elapsedMillis() {
            return FairnessAtomicWitnessDiagnosticTest.elapsedMillis(startedNanos);
        }

        Counts counts() {
            return counts.snapshot();
        }

        Witness bestWitness() {
            return bestWitness;
        }

        private void enumerateReassign() {
            for (int shiftIndex : problem.mutableShiftIndexes()) {
                int oldEmployee = incumbent.employeeIndex(shiftIndex);
                for (int employeeIndex = 0; employeeIndex < problem.employeeCount(); employeeIndex++) {
                    if (employeeIndex != oldEmployee) {
                        evaluate("REASSIGN", FairnessProtectedReassignMove.create(
                                problem, state, shiftIndex, employeeIndex));
                    }
                }
            }
        }

        private void enumerateSwap() {
            int[] mutable = problem.mutableShiftIndexes();
            for (int left = 0; left < mutable.length; left++) {
                for (int right = left + 1; right < mutable.length; right++) {
                    if (incumbent.employeeIndex(mutable[left]) != incumbent.employeeIndex(mutable[right])) {
                        evaluate("SWAP", SwapMove.create(problem, state, mutable[left], mutable[right]));
                    }
                }
            }
        }

        private void enumerateSameDayThreeCycles() {
            Map<String, List<Integer>> byDate = mutableBy(shift -> shift.start().toLocalDate());
            for (List<Integer> group : byDate.values()) {
                enumerateCyclesInGroup("DAY_3_CYCLE", group, 3);
            }
        }

        private void enumerateShiftTypeWindowCycles() {
            Map<String, List<Integer>> byShiftType = mutableBy(
                    shift -> shift.shiftCode().trim().toUpperCase());
            for (List<Integer> typeGroup : byShiftType.values()) {
                for (int start = 0; start < typeGroup.size(); start++) {
                    LocalDate date = problem.shifts().get(typeGroup.get(start)).start().toLocalDate();
                    List<Integer> window = new ArrayList<>();
                    for (int candidate : typeGroup) {
                        long distance = ChronoUnit.DAYS.between(date, problem.shifts().get(candidate).start().toLocalDate());
                        if (distance >= 0L && distance <= 3L) {
                            window.add(candidate);
                        }
                    }
                    enumerateCyclesInGroup("SHIFT_TYPE_WINDOW_3_CYCLE", window, 3);
                    enumerateCyclesInGroup("SHIFT_TYPE_WINDOW_4_CYCLE", window, 4);
                }
            }
        }

        private Map<String, List<Integer>> mutableBy(
                java.util.function.Function<PlanningProblem.ShiftData, ?> key) {
            Map<String, List<Integer>> groups = new LinkedHashMap<>();
            for (int shiftIndex : problem.mutableShiftIndexes()) {
                Object value = key.apply(problem.shifts().get(shiftIndex));
                groups.computeIfAbsent(String.valueOf(value), unused -> new ArrayList<>()).add(shiftIndex);
            }
            return groups;
        }

        private void enumerateCyclesInGroup(String type, List<Integer> group, int length) {
            List<Integer> sorted = group.stream().sorted().toList();
            chooseCycles(type, sorted, length, 0, new ArrayList<>());
        }

        private void chooseCycles(String type, List<Integer> group, int length, int next, List<Integer> selected) {
            if (selected.size() == length) {
                if (!distinctEmployees(selected)) {
                    return;
                }
                for (int[] permutation : cyclePermutations(length)) {
                    List<ChainMove.Leg> legs = new ArrayList<>(length);
                    for (int index = 0; index < length; index++) {
                        int shiftIndex = selected.get(index);
                        int employee = incumbent.employeeIndex(selected.get(permutation[index]));
                        legs.add(new ChainMove.Leg(shiftIndex, employee));
                    }
                    evaluate(type, ChainMove.create(problem, state, legs));
                }
                return;
            }
            for (int index = next; index <= group.size() - (length - selected.size()); index++) {
                selected.add(group.get(index));
                chooseCycles(type, group, length, index + 1, selected);
                selected.removeLast();
            }
        }

        private boolean distinctEmployees(List<Integer> shiftIndexes) {
            return shiftIndexes.stream().map(incumbent::employeeIndex).distinct().count() == shiftIndexes.size();
        }

        private static List<int[]> cyclePermutations(int length) {
            if (length == 3) {
                return List.of(new int[] { 1, 2, 0 }, new int[] { 2, 0, 1 });
            }
            return List.of(
                    new int[] { 1, 2, 3, 0 }, new int[] { 1, 3, 0, 2 }, new int[] { 2, 0, 3, 1 },
                    new int[] { 2, 3, 1, 0 }, new int[] { 3, 0, 1, 2 }, new int[] { 3, 2, 0, 1 });
        }

        private void evaluate(String type, Move move) {
            String candidateKey = move.changes().stream()
                    .sorted(Comparator.comparingInt(change -> change.shiftIndex()))
                    .map(change -> change.shiftIndex() + "->" + change.newEmployeeIndex())
                    .collect(Collectors.joining(","));
            if (!distinctCandidateKeys.add(candidateKey)) {
                return;
            }
            counts.increment(type);
            try (MoveTransaction transaction = MoveTransaction.open(state, incremental)) {
                transaction.apply(move);
                RosterSolution candidate = transaction.verifyCandidate();
                if (FairnessProtectedReassignMove.isProtectedFairnessWitness(incumbent.score(), candidate.score())) {
                    Witness witness = witness(type, move, candidate.score());
                    if (bestWitness == null || witness.compareTo(bestWitness) < 0) {
                        bestWitness = witness;
                    }
                }
                transaction.rollback();
            } catch (org.acme.solver.score.ScoreMismatchException mismatch) {
                scoreMismatches++;
                throw mismatch;
            } catch (org.acme.solver.move.StateCorruptionException corruption) {
                rollbackFailures++;
                throw corruption;
            }
        }

        private Witness witness(String type, Move move, RosterScore candidateScore) {
            List<AssignmentDetail> changes = move.changes().stream().map(change -> {
                PlanningProblem.ShiftData shift = problem.shifts().get(change.shiftIndex());
                return new AssignmentDetail(change.shiftIndex(), shift.planningId(), shift.start().toLocalDate(),
                        shift.shiftCode(), problem.employees().get(change.oldEmployeeIndex()).externalId(),
                        problem.employees().get(change.newEmployeeIndex()).externalId());
            }).sorted(Comparator.comparingInt(AssignmentDetail::shiftIndex)).toList();
            LocalDate firstDate = changes.stream().map(AssignmentDetail::date).min(LocalDate::compareTo).orElseThrow();
            LocalDate lastDate = changes.stream().map(AssignmentDetail::date).max(LocalDate::compareTo).orElseThrow();
            long span = ChronoUnit.DAYS.between(firstDate, lastDate);
            return new Witness(type, changes, incumbent.score(), candidateScore,
                    List.of(candidateScore.hardDeltaFrom(incumbent.score()),
                            candidateScore.softDeltaFrom(incumbent.score(), 0),
                            candidateScore.softDeltaFrom(incumbent.score(), 1),
                            candidateScore.softDeltaFrom(incumbent.score(), 2),
                            candidateScore.softDeltaFrom(incumbent.score(), 3)), span);
        }
    }

    private record DiffEdge(int shiftIndex, long shiftPlanningId, LocalDate date, String shiftType,
            String incumbentEmployee, String optaEmployee) {
    }

    private record AssignmentDetail(int shiftIndex, long shiftPlanningId, LocalDate date, String shiftType,
            String oldEmployee, String newEmployee) {
    }

    private record Witness(String neighborhood, List<AssignmentDetail> changes, RosterScore before,
            RosterScore after, List<Long> delta, long dateSpanDays) implements Comparable<Witness> {
        @Override
        public int compareTo(Witness other) {
            int cardinality = Integer.compare(changes.size(), other.changes.size());
            if (cardinality != 0) {
                return cardinality;
            }
            int span = Long.compare(dateSpanDays, other.dateSpanDays);
            if (span != 0) {
                return span;
            }
            return Integer.compare(changes.getFirst().shiftIndex(), other.changes.getFirst().shiftIndex());
        }
    }

    private record Counts(long reassign, long swap, long day3Cycle, long shiftTypeWindow3Cycle,
            long shiftTypeWindow4Cycle) {
        long total() {
            return reassign + swap + day3Cycle + shiftTypeWindow3Cycle + shiftTypeWindow4Cycle;
        }

        static final class Mutable {
            private long reassign;
            private long swap;
            private long day3Cycle;
            private long shiftTypeWindow3Cycle;
            private long shiftTypeWindow4Cycle;

            void increment(String type) {
                switch (type) {
                    case "REASSIGN" -> reassign++;
                    case "SWAP" -> swap++;
                    case "DAY_3_CYCLE" -> day3Cycle++;
                    case "SHIFT_TYPE_WINDOW_3_CYCLE" -> shiftTypeWindow3Cycle++;
                    case "SHIFT_TYPE_WINDOW_4_CYCLE" -> shiftTypeWindow4Cycle++;
                    default -> throw new IllegalArgumentException("알 수 없는 diagnostic neighborhood: " + type);
                }
            }

            Counts snapshot() {
                return new Counts(reassign, swap, day3Cycle, shiftTypeWindow3Cycle, shiftTypeWindow4Cycle);
            }
        }
    }

    private record SeedResult(long seed, long optaEvaluations, long pojoEvaluations, long optaElapsedMillis,
            long pojoElapsedMillis, RosterScore optaScore, RosterScore incumbentScore, List<DiffEdge> diffGraph,
            Counts counts, Witness witness, long rollbackFailures, long scoreMismatches,
            long enumerationElapsedMillis) {
    }
}
