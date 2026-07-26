package org.acme.solver.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.acme.api.dto.PlanningRequest;
import org.acme.converter.EmployeeScheduleBuilder;
import org.acme.model.EmployeeSchedule;
import org.acme.model.Shift;
import org.acme.solver.adapter.PlanningProblemMapper;
import org.acme.solver.algorithm.EmployeeSchedulingConstraintProvider;
import org.acme.solver.alns.AdaptiveOperatorConfig;
import org.acme.solver.alns.AlnsIterationConfig;
import org.acme.solver.alns.AlnsRunMetrics;
import org.acme.solver.alns.AlnsSolverConfig;
import org.acme.solver.alns.AlnsSolverEngine;
import org.acme.solver.alns.FairnessAwareRegret2Repair;
import org.acme.solver.alns.FairnessHotspotRemoval;
import org.acme.solver.alns.GreedyRepair;
import org.acme.solver.alns.OperatorCompatibilityMatrix;
import org.acme.solver.alns.OperatorStatistics;
import org.acme.solver.alns.PreceptorRelationGroupRemoval;
import org.acme.solver.alns.RandomRemoval;
import org.acme.solver.alns.Regret2Repair;
import org.acme.solver.alns.RelatedShiftRemoval;
import org.acme.solver.alns.RelationAwareRepair;
import org.acme.solver.alns.SaAcceptanceConfig;
import org.acme.solver.benchmark.Phase6BenchmarkStatistics.ScorePair;
import org.acme.solver.benchmark.Phase6BenchmarkStatistics.WinTieLoss;
import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.core.SolveOptions;
import org.acme.solver.core.SolveResult;
import org.acme.solver.initial.InitialSolutionBuilder;
import org.acme.solver.initial.InitialSolutionResult;
import org.acme.solver.optaplanner.OptaPlannerScoreAdapter;
import org.acme.solver.score.FullScoreCalculator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.optaplanner.core.api.score.buildin.bendable.BendableScore;
import org.optaplanner.core.api.solver.Solver;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.core.config.solver.EnvironmentMode;
import org.optaplanner.core.config.solver.SolverConfig;
import org.optaplanner.core.config.solver.termination.TerminationConfig;
import org.optaplanner.core.impl.solver.DefaultSolver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * production 기본값과 분리된 Phase 6 SA/ALNS successive-racing 실험입니다.
 *
 * <p>훈련 case의 동일 seed paired 사전식 순위만 설정 선택에 사용합니다.
 * 검증 case와 잠긴 101..110 holdout은 설정 선택에 사용하지 않습니다.</p>
 */
@Tag("benchmark")
class Phase6AlnsHyperparameterTuningTest {

    private static final int SCHEMA_VERSION = 1;
    private static final Set<Long> LOCKED_HOLDOUT_SEEDS =
            Set.of(101L, 102L, 103L, 104L, 105L, 106L, 107L, 108L, 109L, 110L);
    private static final List<String> DEFAULT_DATASETS = List.of(
            "fairness.json", "preceptor.json", "request.json", "sample.json");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final EmployeeScheduleBuilder scheduleBuilder = new EmployeeScheduleBuilder();
    private final PlanningProblemMapper problemMapper = new PlanningProblemMapper();
    private final FullScoreCalculator fullScoreCalculator = new FullScoreCalculator();

    @Test
    void tuneAndCompare() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("phase6.alns-tuning.enabled"),
                "ALNS tuning은 scripts/benchmark/run-phase6-alns-tuning.sh로만 실행합니다.");

        TuningConfig config = TuningConfig.fromSystemProperties();
        prepareOutput(config);
        appendRaw(config, metadata(config));

        List<TuningCase> trainingCases = loadCases(config.datasets(), config.trainingSeeds());
        List<TuningCase> validationCases = loadCases(config.datasets(), config.validationSeeds());
        SearchConfig productionBaseline = SearchConfig.baseline();
        List<StageResult> stages = new ArrayList<>();

        List<SearchConfig> leaders = List.of(productionBaseline);
        leaders = runStage(
                "01-sa",
                withBaseline(expandSa(leaders), productionBaseline),
                trainingCases,
                config.trainingEvaluationLimit(),
                2,
                config,
                stages);
        leaders = runStage(
                "02-calibration",
                withBaseline(expandCalibration(leaders), productionBaseline),
                trainingCases,
                config.trainingEvaluationLimit(),
                2,
                config,
                stages);
        leaders = runStage(
                "03-destroy",
                withBaseline(expandDestroy(leaders), productionBaseline),
                trainingCases,
                config.trainingEvaluationLimit(),
                2,
                config,
                stages);
        leaders = runStage(
                "04-repair",
                withBaseline(expandRepair(leaders), productionBaseline),
                trainingCases,
                config.trainingEvaluationLimit(),
                2,
                config,
                stages);
        leaders = runStage(
                "05-adaptive",
                withBaseline(expandAdaptive(leaders), productionBaseline),
                trainingCases,
                config.trainingEvaluationLimit(),
                3,
                config,
                stages);
        leaders = runStage(
                "06-operator-set",
                withBaseline(expandFairnessOperator(leaders), productionBaseline),
                trainingCases,
                config.trainingEvaluationLimit(),
                config.validationCandidateCount(),
                config,
                stages);

        List<SearchConfig> validationCandidates = leaders.stream()
                .limit(config.validationCandidateCount())
                .toList();
        StageResult validation = executeStage(
                "07-validation",
                validationCandidates,
                validationCases,
                config.validationEvaluationLimit(),
                config,
                false);
        stages.add(validation);
        List<SearchConfig> validatedLeaders = validation.rankings().stream()
                .limit(config.comparisonCandidateCount())
                .map(ranking -> validation.configById().get(ranking.configId()))
                .toList();

        if (config.warmup()) {
            warmUp(validatedLeaders.getFirst(), trainingCases.getFirst(), config);
        }

        List<ComparisonResult> comparisons = new ArrayList<>();
        comparisons.add(runComparison(
                Profile.FIXED_EVALUATIONS,
                validatedLeaders,
                validationCases,
                config,
                new LinkedHashMap<>()));
        if (config.wallClockSeconds() > 0L) {
            comparisons.add(runComparison(
                    Profile.WALL_CLOCK,
                    validatedLeaders,
                    validationCases,
                    config,
                    new LinkedHashMap<>()));
        }

        writeSummary(config, stages, comparisons, validatedLeaders);
        writeReport(config, stages, comparisons, validatedLeaders);

        assertEquals(0L, comparisons.stream()
                .flatMap(comparison -> comparison.candidateRuns().stream())
                .filter(run -> run.rollbackFailures() > 0L
                        || run.scoreMismatchFailures() > 0L
                        || run.stateCorruptionFailures() > 0L)
                .count(), "rollback/score/state 검증 실패가 없어야 합니다.");
    }

    private List<SearchConfig> runStage(
            String name,
            List<SearchConfig> candidates,
            List<TuningCase> cases,
            long evaluationLimit,
            int keep,
            TuningConfig config,
            List<StageResult> sink) throws Exception {
        StageResult result = executeStage(name, candidates, cases, evaluationLimit, config, true);
        sink.add(result);
        List<SearchConfig> leaders = result.rankings().stream()
                .limit(Math.min(keep, result.rankings().size()))
                .map(ranking -> result.configById().get(ranking.configId()))
                .toList();
        System.out.printf(Locale.ROOT,
                "[ALNS tuning] %s 완료: 후보=%d, case=%d, leader=%s%n",
                name, candidates.size(), cases.size(), leaders.getFirst().id());
        return leaders;
    }

    private StageResult executeStage(
            String name,
            List<SearchConfig> candidates,
            List<TuningCase> cases,
            long evaluationLimit,
            TuningConfig tuningConfig,
            boolean training) throws Exception {
        Map<String, SearchConfig> configById = new LinkedHashMap<>();
        for (SearchConfig candidate : candidates) {
            configById.putIfAbsent(candidate.id(), candidate);
        }
        List<AlnsObservation> observations = new ArrayList<>();
        int total = configById.size() * cases.size();
        int completed = 0;
        for (SearchConfig candidate : configById.values()) {
            for (TuningCase tuningCase : cases) {
                AlnsObservation observation = runAlns(
                        name, candidate, tuningCase, Profile.FIXED_EVALUATIONS,
                        evaluationLimit, 0L);
                observations.add(observation);
                appendRaw(tuningConfig, objectMapper.valueToTree(observation));
                completed++;
                if (completed == total || completed % Math.max(1, cases.size()) == 0) {
                    System.out.printf(Locale.ROOT,
                            "[ALNS tuning] %s %d/%d, config=%s%n",
                            name, completed, total, candidate.id());
                }
            }
        }
        List<Ranking> rankings = rank(configById.values().stream().toList(), cases, observations);
        StageResult result = new StageResult(
                name,
                training ? "training" : "validation",
                evaluationLimit,
                Map.copyOf(configById),
                List.copyOf(observations),
                rankings);
        ObjectNode stageRecord = objectMapper.createObjectNode();
        stageRecord.put("record_type", "stage_ranking");
        stageRecord.set("stage", objectMapper.valueToTree(result));
        appendRaw(tuningConfig, stageRecord);
        return result;
    }

    private AlnsObservation runAlns(
            String stage,
            SearchConfig searchConfig,
            TuningCase tuningCase,
            Profile profile,
            long evaluationLimit,
            long wallClockSeconds) {
        AlnsSolverEngine engine = createAlnsEngine(searchConfig);
        SolveOptions options = profile == Profile.FIXED_EVALUATIONS
                ? SolveOptions.builder()
                        .maxEvaluations(evaluationLimit)
                        .randomSeed(tuningCase.seed())
                        .warmStart(tuningCase.warmStart())
                        .build()
                : SolveOptions.builder()
                        .spentLimit(Duration.ofSeconds(wallClockSeconds))
                        .randomSeed(tuningCase.seed())
                        .warmStart(tuningCase.warmStart())
                        .build();
        long startedNanos = System.nanoTime();
        SolveResult<RosterSolution> result = engine.solve(
                tuningCase.problem(), options, ignored -> {
                });
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        if (result.score() == null || result.bestSolution() == null) {
            throw new IllegalStateException(
                    "ALNS가 complete solution을 반환하지 못했습니다: " + result.terminationReason());
        }
        AlnsRunMetrics metrics = (AlnsRunMetrics) result.metrics();
        long bestEvaluation = metrics.bestImprovements().isEmpty()
                ? 0L : metrics.bestImprovements().getLast().evaluation();
        String finalBestDestroy = metrics.bestImprovements().isEmpty()
                ? null : metrics.bestImprovements().getLast().destroyOperatorId();
        String finalBestRepair = metrics.bestImprovements().isEmpty()
                ? null : metrics.bestImprovements().getLast().repairOperatorId();
        return new AlnsObservation(
                "alns_run",
                stage,
                profile.id,
                searchConfig.id(),
                tuningCase.dataset(),
                tuningCase.sha256(),
                tuningCase.seed(),
                result.score(),
                result.score().isFeasible(),
                elapsedMillis,
                result.evaluationCount(),
                bestEvaluation,
                result.terminationReason().name(),
                metrics.rollbackAttemptCount(),
                metrics.rollbackFailureCount(),
                metrics.destroyFailures(),
                metrics.repairFailures(),
                metrics.operatorExceptions(),
                metrics.scoreMismatchFailures(),
                metrics.stateCorruptionFailures(),
                metrics.observedInitialWorseningAcceptanceRate(),
                metrics.calibration().evaluatedCandidates(),
                metrics.destroyOperators(),
                metrics.repairOperators(),
                finalBestDestroy,
                finalBestRepair);
    }

    private ComparisonResult runComparison(
            Profile profile,
            List<SearchConfig> candidates,
            List<TuningCase> cases,
            TuningConfig config,
            Map<String, OptaObservation> optaCache) throws Exception {
        List<AlnsObservation> candidateRuns = new ArrayList<>();
        List<OptaObservation> optaRuns = new ArrayList<>();
        for (int caseIndex = 0; caseIndex < cases.size(); caseIndex++) {
            TuningCase tuningCase = cases.get(caseIndex);
            boolean optaFirst = Math.floorMod(caseIndex, 2) == 0;
            if (optaFirst) {
                optaRuns.add(cachedOpta(profile, tuningCase, config, optaCache));
            }
            List<SearchConfig> crossedCandidates = crossedCandidateOrder(candidates, caseIndex);
            for (SearchConfig candidate : crossedCandidates) {
                AlnsObservation run = runAlns(
                        "08-comparison",
                        candidate,
                        tuningCase,
                        profile,
                        config.comparisonPojoEvaluationLimit(),
                        config.wallClockSeconds());
                candidateRuns.add(run);
                appendRaw(config, objectMapper.valueToTree(run));
            }
            if (!optaFirst) {
                optaRuns.add(cachedOpta(profile, tuningCase, config, optaCache));
            }
            System.out.printf(Locale.ROOT,
                    "[ALNS tuning] comparison %s %d/%d 완료%n",
                    profile.id, caseIndex + 1, cases.size());
        }
        optaRuns.sort(Comparator.comparing(OptaObservation::caseKey));
        writeOptaCache(config, profile, optaCache);
        List<ComparisonSummary> summaries = candidates.stream()
                .map(candidate -> summarizeComparison(candidate, candidateRuns, optaRuns))
                .toList();
        return new ComparisonResult(
                profile.id,
                true,
                "case index별 Opta-first/ALNS-first 교차, 다중 후보의 Opta 결과는 case별 1회 캐시",
                List.copyOf(candidateRuns),
                List.copyOf(optaRuns),
                summaries);
    }

    private OptaObservation cachedOpta(
            Profile profile,
            TuningCase tuningCase,
            TuningConfig config,
            Map<String, OptaObservation> cache) throws Exception {
        String key = profile.id + "|" + tuningCase.caseKey();
        OptaObservation cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        OptaObservation computed = runOpta(profile, tuningCase, config);
        cache.put(key, computed);
        appendRaw(config, objectMapper.valueToTree(computed));
        return computed;
    }

    private OptaObservation runOpta(
            Profile profile,
            TuningCase tuningCase,
            TuningConfig config) {
        EmployeeSchedule problem = scheduleBuilder.build(tuningCase.request());
        TerminationConfig termination = profile == Profile.FIXED_EVALUATIONS
                ? new TerminationConfig().withScoreCalculationCountLimit(
                        config.optaPlannerEvaluationLimit())
                : new TerminationConfig().withSpentLimit(
                        Duration.ofSeconds(config.wallClockSeconds()));
        SolverConfig solverConfig = new SolverConfig()
                .withSolutionClass(EmployeeSchedule.class)
                .withEntityClasses(Shift.class)
                .withConstraintProviderClass(EmployeeSchedulingConstraintProvider.class)
                .withTerminationConfig(termination)
                .withMoveThreadCount("NONE")
                .withEnvironmentMode(EnvironmentMode.REPRODUCIBLE)
                .withRandomSeed(tuningCase.seed());
        Solver<EmployeeSchedule> solver =
                SolverFactory.<EmployeeSchedule>create(solverConfig).buildSolver();
        DefaultSolver<EmployeeSchedule> defaultSolver = (DefaultSolver<EmployeeSchedule>) solver;
        AtomicLong bestEvaluation = new AtomicLong();
        solver.addEventListener(event -> bestEvaluation.set(
                defaultSolver.getSolverScope().getScoreCalculationCount()));
        long startedNanos = System.nanoTime();
        EmployeeSchedule solution = solver.solve(problem);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        BendableScore bendableScore = Objects.requireNonNull(solution.getScore(), "OptaPlanner score");
        if (solution.getShiftList().stream().anyMatch(shift -> shift.getEmployee() == null)) {
            throw new IllegalStateException(
                    "OptaPlanner가 incomplete solution을 반환했습니다: " + bendableScore);
        }
        RosterScore score = OptaPlannerScoreAdapter.toRosterScore(bendableScore);
        return new OptaObservation(
                "opta_run",
                profile.id,
                tuningCase.dataset(),
                tuningCase.sha256(),
                tuningCase.seed(),
                score,
                score.isFeasible(),
                elapsedMillis,
                defaultSolver.getSolverScope().getScoreCalculationCount(),
                bestEvaluation.get());
    }

    private void warmUp(
            SearchConfig leader,
            TuningCase tuningCase,
            TuningConfig config) {
        long alnsWarmupEvaluations = Math.min(100L, config.comparisonPojoEvaluationLimit());
        runAlns("warmup", leader, tuningCase, Profile.FIXED_EVALUATIONS,
                alnsWarmupEvaluations, 0L);
        TuningConfig warmupConfig = config.withOptaPlannerEvaluationLimit(
                Math.min(10_000L, config.optaPlannerEvaluationLimit()));
        runOpta(Profile.FIXED_EVALUATIONS, tuningCase, warmupConfig);
        if (config.wallClockSeconds() > 0L) {
            runAlns("warmup", leader, tuningCase, Profile.WALL_CLOCK,
                    1L, 1L);
            runOpta(Profile.WALL_CLOCK, tuningCase, warmupConfig.withWallClockSeconds(1L));
        }
        System.out.println("[ALNS tuning] warm-up 완료(집계 제외)");
    }

    private ComparisonSummary summarizeComparison(
            SearchConfig candidate,
            List<AlnsObservation> candidateRuns,
            List<OptaObservation> optaRuns) {
        Map<String, AlnsObservation> candidateByCase = new LinkedHashMap<>();
        candidateRuns.stream()
                .filter(run -> run.configId().equals(candidate.id()))
                .forEach(run -> candidateByCase.put(run.caseKey(), run));
        Map<String, OptaObservation> optaByCase = new LinkedHashMap<>();
        optaRuns.forEach(run -> optaByCase.put(run.caseKey(), run));
        List<ScorePair> pairs = optaByCase.keySet().stream()
                .map(key -> new ScorePair(
                        optaByCase.get(key).score(),
                        Objects.requireNonNull(candidateByCase.get(key), "candidate case " + key).score()))
                .toList();
        WinTieLoss wtl = Phase6BenchmarkStatistics.winTieLoss(pairs);
        List<AlnsObservation> selectedRuns = candidateByCase.values().stream().toList();
        List<OperatorAggregate> destroys = aggregateOperators(selectedRuns, true);
        List<OperatorAggregate> repairs = aggregateOperators(selectedRuns, false);
        return new ComparisonSummary(
                candidate.id(),
                pairs.size(),
                optaRuns.stream().filter(OptaObservation::feasible).count(),
                selectedRuns.stream().filter(AlnsObservation::feasible).count(),
                wtl.wins(),
                wtl.ties(),
                wtl.losses(),
                quantiles(optaRuns.stream().map(OptaObservation::score).toList()),
                quantiles(selectedRuns.stream().map(AlnsObservation::score).toList()),
                Phase6BenchmarkStatistics.pairedDeltaQuantile(pairs, 0.10d),
                Phase6BenchmarkStatistics.pairedDeltaQuantile(pairs, 0.50d),
                Phase6BenchmarkStatistics.pairedDeltaQuantile(pairs, 0.90d),
                Phase6BenchmarkStatistics.nearestRankLong(
                        optaRuns.stream().map(OptaObservation::elapsedMillis).toList(), 0.95d),
                Phase6BenchmarkStatistics.nearestRankLong(
                        selectedRuns.stream().map(AlnsObservation::elapsedMillis).toList(), 0.95d),
                Phase6BenchmarkStatistics.nearestRankLong(
                        optaRuns.stream().map(OptaObservation::bestEvaluation).toList(), 0.50d),
                Phase6BenchmarkStatistics.nearestRankLong(
                        optaRuns.stream().map(OptaObservation::bestEvaluation).toList(), 0.90d),
                Phase6BenchmarkStatistics.nearestRankLong(
                        selectedRuns.stream().map(AlnsObservation::bestEvaluation).toList(), 0.50d),
                Phase6BenchmarkStatistics.nearestRankLong(
                        selectedRuns.stream().map(AlnsObservation::bestEvaluation).toList(), 0.90d),
                selectedRuns.stream().mapToLong(AlnsObservation::rollbackAttempts).sum(),
                selectedRuns.stream().mapToLong(AlnsObservation::rollbackFailures).sum(),
                destroys,
                repairs);
    }

    private static ScoreQuantiles quantiles(List<RosterScore> scores) {
        return new ScoreQuantiles(
                Phase6BenchmarkStatistics.nearestRankScore(scores, 0.10d),
                Phase6BenchmarkStatistics.nearestRankScore(scores, 0.50d),
                Phase6BenchmarkStatistics.nearestRankScore(scores, 0.90d));
    }

    static List<Ranking> rank(
            List<SearchConfig> configs,
            List<TuningCase> cases,
            List<AlnsObservation> observations) {
        Map<String, Map<String, AlnsObservation>> byConfig = new LinkedHashMap<>();
        for (SearchConfig config : configs) {
            byConfig.put(config.id(), new LinkedHashMap<>());
        }
        for (AlnsObservation observation : observations) {
            Map<String, AlnsObservation> byCase = byConfig.get(observation.configId());
            if (byCase != null) {
                byCase.put(observation.caseKey(), observation);
            }
        }
        List<Ranking> result = new ArrayList<>();
        for (SearchConfig config : configs) {
            Map<String, AlnsObservation> current = byConfig.get(config.id());
            if (current.size() != cases.size()) {
                throw new IllegalArgumentException(
                        "모든 config는 동일 case를 완료해야 합니다: " + config.id());
            }
            long feasible = current.values().stream().filter(AlnsObservation::feasible).count();
            long wins = 0L;
            long ties = 0L;
            long losses = 0L;
            long ordinalRankSum = 0L;
            for (TuningCase tuningCase : cases) {
                RosterScore score = current.get(tuningCase.caseKey()).score();
                long better = 0L;
                for (SearchConfig other : configs) {
                    if (other.id().equals(config.id())) {
                        continue;
                    }
                    RosterScore otherScore = byConfig.get(other.id())
                            .get(tuningCase.caseKey()).score();
                    int comparison = score.compareTo(otherScore);
                    if (comparison > 0) {
                        wins++;
                    } else if (comparison < 0) {
                        losses++;
                        better++;
                    } else {
                        ties++;
                    }
                }
                ordinalRankSum += 1L + better;
            }
            List<AlnsObservation> runs = current.values().stream().toList();
            result.add(new Ranking(
                    config.id(),
                    feasible,
                    wins,
                    ties,
                    losses,
                    ordinalRankSum,
                    config.changedParameterCount(),
                    Phase6BenchmarkStatistics.nearestRankLong(
                            runs.stream().map(AlnsObservation::elapsedMillis).toList(), 0.95d),
                    runs.stream().mapToLong(AlnsObservation::rollbackFailures).sum(),
                    runs.stream().mapToLong(run -> run.destroyOperators().stream()
                            .mapToLong(OperatorStatistics::globalBestCount).sum()).sum()));
        }
        result.sort(Comparator
                .comparingLong(Ranking::feasibleCount).reversed()
                .thenComparing(Comparator.comparingLong(Ranking::pairwiseWins).reversed())
                .thenComparingLong(Ranking::pairwiseLosses)
                .thenComparingLong(Ranking::ordinalRankSum)
                .thenComparingLong(Ranking::rollbackFailures)
                .thenComparingInt(Ranking::changedParameterCount)
                .thenComparing(Ranking::configId));
        return List.copyOf(result);
    }

    private AlnsSolverEngine createAlnsEngine(SearchConfig config) {
        Map<String, Set<String>> compatibility =
                new LinkedHashMap<>(OperatorCompatibilityMatrix.baseline().asMap());
        List<org.acme.solver.alns.DestroyOperator> destroys = new ArrayList<>(List.of(
                new RandomRemoval(),
                new RelatedShiftRemoval(),
                new PreceptorRelationGroupRemoval()));
        List<org.acme.solver.alns.RepairOperator> repairs = new ArrayList<>(List.of(
                new GreedyRepair(),
                new Regret2Repair(),
                new RelationAwareRepair()));
        if (config.fairnessOperator()) {
            destroys.add(new FairnessHotspotRemoval());
            repairs.add(new FairnessAwareRegret2Repair());
            compatibility.put(
                    FairnessHotspotRemoval.ID,
                    Set.of(FairnessAwareRegret2Repair.ID));
        }
        return new AlnsSolverEngine(
                new InitialSolutionBuilder(fullScoreCalculator),
                fullScoreCalculator,
                destroys,
                repairs,
                new OperatorCompatibilityMatrix(compatibility),
                new AlnsSolverConfig(
                        new AlnsIterationConfig(
                                config.destroyRate(), 1, config.qMax(), 12,
                                config.maxRepairAttempts()),
                        new SaAcceptanceConfig(
                                config.initialAcceptanceProbability(),
                                config.finalTemperatureRatio(),
                                10_000L),
                        new AdaptiveOperatorConfig(
                                1.0d, 0.1d, config.reactionFactor(), config.segmentLength(),
                                10.0d, 5.0d, 1.0d, 0.0d),
                        10_000L,
                        config.calibrationAttempts(),
                        Duration.ofMillis(10L)));
    }

    private List<TuningCase> loadCases(List<String> datasets, List<Long> seeds) throws Exception {
        List<TuningCase> result = new ArrayList<>();
        for (String dataset : datasets) {
            byte[] bytes;
            try (InputStream input = getClass().getResourceAsStream("/json/" + dataset)) {
                if (input == null) {
                    throw new IllegalArgumentException("benchmark 입력을 찾을 수 없습니다: " + dataset);
                }
                bytes = input.readAllBytes();
            }
            PlanningRequest request = objectMapper.readValue(bytes, PlanningRequest.class);
            PlanningProblem problem = problemMapper.toPlanningProblem(scheduleBuilder.build(request));
            InitialSolutionResult initial = new InitialSolutionBuilder(fullScoreCalculator).build(problem);
            if (!initial.succeeded()) {
                throw new IllegalStateException("초기해 생성 실패: " + dataset + " " + initial.failures());
            }
            for (long seed : seeds) {
                result.add(new TuningCase(
                        dataset, sha256(bytes), seed, request, problem, initial.solution()));
            }
        }
        return List.copyOf(result);
    }

    private static List<SearchConfig> expandSa(List<SearchConfig> bases) {
        List<SearchConfig> result = new ArrayList<>();
        for (SearchConfig base : bases) {
            for (double initialProbability : List.of(0.05d, 0.10d, 0.20d, 0.35d)) {
                for (double finalRatio : List.of(0.001d, 0.005d, 0.01d, 0.05d)) {
                    result.add(base.withSa(initialProbability, finalRatio));
                }
            }
        }
        return deduplicate(result);
    }

    private static List<SearchConfig> expandCalibration(List<SearchConfig> bases) {
        List<SearchConfig> result = new ArrayList<>();
        for (SearchConfig base : bases) {
            for (int attempts : List.of(32, 64, 128)) {
                result.add(base.withCalibrationAttempts(attempts));
            }
        }
        return deduplicate(result);
    }

    private static List<SearchConfig> expandDestroy(List<SearchConfig> bases) {
        List<SearchConfig> result = new ArrayList<>();
        for (SearchConfig base : bases) {
            for (double rate : List.of(0.02d, 0.05d, 0.08d, 0.12d)) {
                for (int qMax : List.of(4, 8, 12)) {
                    result.add(base.withDestroy(rate, qMax));
                }
            }
        }
        return deduplicate(result);
    }

    private static List<SearchConfig> expandRepair(List<SearchConfig> bases) {
        List<SearchConfig> result = new ArrayList<>();
        for (SearchConfig base : bases) {
            for (int attempts : List.of(1, 2, 3)) {
                result.add(base.withRepairAttempts(attempts));
            }
        }
        return deduplicate(result);
    }

    private static List<SearchConfig> expandAdaptive(List<SearchConfig> bases) {
        List<SearchConfig> result = new ArrayList<>();
        for (SearchConfig base : bases) {
            for (double reaction : List.of(0.05d, 0.10d, 0.20d, 0.40d)) {
                for (int segment : List.of(50, 100, 250)) {
                    result.add(base.withAdaptive(reaction, segment));
                }
            }
        }
        return deduplicate(result);
    }

    private static List<SearchConfig> expandFairnessOperator(List<SearchConfig> bases) {
        List<SearchConfig> result = new ArrayList<>();
        for (SearchConfig base : bases) {
            result.add(base.withFairnessOperator(false));
            result.add(base.withFairnessOperator(true));
        }
        return deduplicate(result);
    }

    private static List<SearchConfig> withBaseline(
            List<SearchConfig> candidates, SearchConfig baseline) {
        List<SearchConfig> result = new ArrayList<>(candidates);
        result.add(baseline);
        return deduplicate(result);
    }

    private static List<SearchConfig> deduplicate(List<SearchConfig> values) {
        Map<String, SearchConfig> byId = new LinkedHashMap<>();
        values.forEach(value -> byId.putIfAbsent(value.id(), value));
        return List.copyOf(byId.values());
    }

    private static List<SearchConfig> crossedCandidateOrder(
            List<SearchConfig> candidates, int offset) {
        List<SearchConfig> result = new ArrayList<>(candidates);
        if (!result.isEmpty()) {
            java.util.Collections.rotate(result, Math.floorMod(offset, result.size()));
        }
        return List.copyOf(result);
    }

    private static List<OperatorAggregate> aggregateOperators(
            List<AlnsObservation> runs, boolean destroy) {
        Map<String, MutableOperatorAggregate> aggregate = new LinkedHashMap<>();
        for (AlnsObservation run : runs) {
            List<OperatorStatistics> statistics =
                    destroy ? run.destroyOperators() : run.repairOperators();
            for (OperatorStatistics statistic : statistics) {
                aggregate.computeIfAbsent(
                        statistic.operatorId(), MutableOperatorAggregate::new)
                        .add(statistic);
            }
            String finalBest = destroy ? run.finalBestDestroy() : run.finalBestRepair();
            if (finalBest != null) {
                aggregate.computeIfAbsent(finalBest, MutableOperatorAggregate::new)
                        .finalBestCount++;
            }
        }
        return aggregate.values().stream()
                .map(MutableOperatorAggregate::snapshot)
                .sorted(Comparator.comparing(OperatorAggregate::operatorId))
                .toList();
    }

    private ObjectNode metadata(TuningConfig config) {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("record_type", "metadata");
        json.put("schema_version", SCHEMA_VERSION);
        json.put("captured_at", Instant.now().toString());
        json.put("experiment", "PHASE6_ALNS_HYPERPARAMETER_SUCCESSIVE_RACING");
        json.put("production_defaults_changed", false);
        json.put("promotion_gate_applied", false);
        json.put("selection_rule",
                "동일 case별 lexicographic feasible/pairwise WTL/ordinal-rank; 설정 선택 전용이며 promotion gate가 아님");
        json.put("fixed_evaluation_note",
                "OptaPlanner=score calculation, ALNS=complete candidate evaluation; throughput으로 직접 비교하지 않음");
        json.put("warmup_excluded", config.warmup());
        json.put("comparison_order_crossed", true);
        json.set("config", objectMapper.valueToTree(config));
        return json;
    }

    private void prepareOutput(TuningConfig config) throws Exception {
        Files.createDirectories(config.outputDirectory());
        Files.writeString(config.rawOutput(), "", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void appendRaw(TuningConfig config, ObjectNode record) throws Exception {
        Files.writeString(
                config.rawOutput(),
                objectMapper.writeValueAsString(record) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private void writeOptaCache(
            TuningConfig config,
            Profile profile,
            Map<String, OptaObservation> cache) throws Exception {
        Path output = config.outputDirectory().resolve("opta-cache-" + profile.id + ".json");
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schema_version", SCHEMA_VERSION);
        root.put("profile", profile.id);
        root.put("cache_scope",
                "동일 comparison 실행에서 후보별 OptaPlanner 중복 실행 방지; 다른 환경 재사용 금지");
        root.set("entries", objectMapper.valueToTree(cache));
        Files.writeString(
                output,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root)
                        + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void writeSummary(
            TuningConfig config,
            List<StageResult> stages,
            List<ComparisonResult> comparisons,
            List<SearchConfig> leaders) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schema_version", SCHEMA_VERSION);
        root.put("production_defaults_changed", false);
        root.put("promotion_gate_applied", false);
        root.set("tuning_leaders", objectMapper.valueToTree(leaders));
        root.set("stages", objectMapper.valueToTree(stages));
        root.set("comparisons", objectMapper.valueToTree(comparisons));
        Files.writeString(
                config.summaryOutput(),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root)
                        + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void writeReport(
            TuningConfig config,
            List<StageResult> stages,
            List<ComparisonResult> comparisons,
            List<SearchConfig> leaders) throws Exception {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# Phase 6 SA/ALNS 하이퍼파라미터 레이싱\n\n")
                .append("- 생성 시각: `").append(Instant.now()).append("`\n")
                .append("- production 기본값 변경: 없음\n")
                .append("- promotion gate 적용: 없음\n")
                .append("- 훈련 seed: `").append(config.trainingSeeds()).append("`\n")
                .append("- 검증 seed: `").append(config.validationSeeds()).append("`\n")
                .append("- 잠긴 기존 holdout 101..110: 설정 선택에 사용하지 않음\n")
                .append("- fixed-evaluation: OptaPlanner score calculation과 ALNS complete candidate evaluation은 ")
                .append("서로 다른 단위이므로 throughput 비교로 해석하지 않음\n")
                .append("- 설정 선택: case별 사전식 feasible → pairwise 승 → 패 → ordinal rank sum; ")
                .append("수치 promotion gate가 아님\n\n")
                .append("## 단계별 리더\n\n")
                .append("| 단계 | partition | 후보 | case | eval | 1위 | feasible | pair W/T/L | rank sum | global best |\n")
                .append("|---|---|---:|---:|---:|---|---:|---:|---:|---:|\n");
        for (StageResult stage : stages) {
            Ranking first = stage.rankings().getFirst();
            markdown.append("| ").append(stage.stage())
                    .append(" | ").append(stage.partition())
                    .append(" | ").append(stage.configById().size())
                    .append(" | ").append(stage.observations().size() / stage.configById().size())
                    .append(" | ").append(stage.evaluationLimit())
                    .append(" | `").append(first.configId()).append("`")
                    .append(" | ").append(first.feasibleCount())
                    .append(" | ").append(first.pairwiseWins()).append('/')
                    .append(first.pairwiseTies()).append('/')
                    .append(first.pairwiseLosses())
                    .append(" | ").append(first.ordinalRankSum())
                    .append(" | ").append(first.globalBestCount())
                    .append(" |\n");
        }
        markdown.append("\n## 검증 후 비교 후보\n\n");
        for (int index = 0; index < leaders.size(); index++) {
            markdown.append(index + 1).append(". `")
                    .append(leaders.get(index).id()).append("`: `")
                    .append(leaders.get(index)).append("`\n");
        }
        markdown.append("\n## OptaPlanner paired 비교\n\n")
                .append("| profile | config | pairs | feasible Opta/ALNS | W/T/L | ")
                .append("Opta score p10/median/p90 | ALNS score p10/median/p90 | ")
                .append("paired delta p10/median/p90 | p95 ms Opta/ALNS | ")
                .append("best eval p50/p90 Opta | best eval p50/p90 ALNS | rollback attempt/fail |\n")
                .append("|---|---|---:|---:|---:|---|---|---|---:|---|---|---:|\n");
        for (ComparisonResult comparison : comparisons) {
            for (ComparisonSummary summary : comparison.summaries()) {
                markdown.append("| ").append(comparison.profile())
                        .append(" | `").append(summary.configId()).append("`")
                        .append(" | ").append(summary.pairCount())
                        .append(" | ").append(summary.optaFeasible()).append('/')
                        .append(summary.candidateFeasible())
                        .append(" | ").append(summary.wins()).append('/')
                        .append(summary.ties()).append('/')
                        .append(summary.losses())
                        .append(" | ").append(summary.optaScores().display())
                        .append(" | ").append(summary.candidateScores().display())
                        .append(" | ").append(Arrays.toString(summary.deltaP10())).append(" / ")
                        .append(Arrays.toString(summary.deltaMedian())).append(" / ")
                        .append(Arrays.toString(summary.deltaP90()))
                        .append(" | ").append(summary.optaElapsedP95()).append('/')
                        .append(summary.candidateElapsedP95())
                        .append(" | ").append(summary.optaBestEvaluationP50()).append('/')
                        .append(summary.optaBestEvaluationP90())
                        .append(" | ").append(summary.candidateBestEvaluationP50()).append('/')
                        .append(summary.candidateBestEvaluationP90())
                        .append(" | ").append(summary.rollbackAttempts()).append('/')
                        .append(summary.rollbackFailures())
                        .append(" |\n");
            }
        }
        markdown.append("\n## 연산자 선택·기여\n\n");
        for (ComparisonResult comparison : comparisons) {
            for (ComparisonSummary summary : comparison.summaries()) {
                markdown.append("### ").append(comparison.profile()).append(" / `")
                        .append(summary.configId()).append("`\n\n")
                        .append("| 종류 | operator | 선택 | global best | current 개선 | 악화 수락 | 거절 | final best |\n")
                        .append("|---|---|---:|---:|---:|---:|---:|---:|\n");
                appendOperatorRows(markdown, "destroy", summary.destroyOperators());
                appendOperatorRows(markdown, "repair", summary.repairOperators());
                markdown.append('\n');
            }
        }
        markdown.append("## 판정 경계\n\n")
                .append("이 문서는 테스트 전용 튜닝 리더를 보고할 뿐 기본 활성화나 승격을 선언하지 않는다. ")
                .append("Phase 0의 사용자 합의 수치 gate와 새 blind holdout이 필요하다.\n");
        Files.writeString(
                config.reportOutput(),
                markdown.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static void appendOperatorRows(
            StringBuilder markdown,
            String type,
            List<OperatorAggregate> operators) {
        for (OperatorAggregate operator : operators) {
            markdown.append("| ").append(type)
                    .append(" | ").append(operator.operatorId())
                    .append(" | ").append(operator.selections())
                    .append(" | ").append(operator.globalBest())
                    .append(" | ").append(operator.currentImprovement())
                    .append(" | ").append(operator.acceptedWorsening())
                    .append(" | ").append(operator.rejections())
                    .append(" | ").append(operator.finalBest())
                    .append(" |\n");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 계산 실패", exception);
        }
    }

    record SearchConfig(
            double initialAcceptanceProbability,
            double finalTemperatureRatio,
            int calibrationAttempts,
            double destroyRate,
            int qMax,
            int maxRepairAttempts,
            double reactionFactor,
            int segmentLength,
            boolean fairnessOperator) {

        static SearchConfig baseline() {
            return new SearchConfig(
                    0.20d, 0.01d, 64, 0.05d, 8, 2, 0.20d, 100, false);
        }

        String id() {
            return String.format(Locale.ROOT,
                    "p%s-r%s-c%d-d%s-q%d-a%d-x%s-s%d-f%s",
                    decimalId(initialAcceptanceProbability),
                    decimalId(finalTemperatureRatio),
                    calibrationAttempts,
                    decimalId(destroyRate),
                    qMax,
                    maxRepairAttempts,
                    decimalId(reactionFactor),
                    segmentLength,
                    fairnessOperator ? "on" : "off");
        }

        SearchConfig withSa(double probability, double ratio) {
            return new SearchConfig(
                    probability, ratio, calibrationAttempts, destroyRate, qMax,
                    maxRepairAttempts, reactionFactor, segmentLength, fairnessOperator);
        }

        SearchConfig withCalibrationAttempts(int attempts) {
            return new SearchConfig(
                    initialAcceptanceProbability, finalTemperatureRatio, attempts,
                    destroyRate, qMax, maxRepairAttempts, reactionFactor, segmentLength,
                    fairnessOperator);
        }

        SearchConfig withDestroy(double rate, int maximum) {
            return new SearchConfig(
                    initialAcceptanceProbability, finalTemperatureRatio, calibrationAttempts,
                    rate, maximum, maxRepairAttempts, reactionFactor, segmentLength,
                    fairnessOperator);
        }

        SearchConfig withRepairAttempts(int attempts) {
            return new SearchConfig(
                    initialAcceptanceProbability, finalTemperatureRatio, calibrationAttempts,
                    destroyRate, qMax, attempts, reactionFactor, segmentLength,
                    fairnessOperator);
        }

        SearchConfig withAdaptive(double reaction, int segment) {
            return new SearchConfig(
                    initialAcceptanceProbability, finalTemperatureRatio, calibrationAttempts,
                    destroyRate, qMax, maxRepairAttempts, reaction, segment,
                    fairnessOperator);
        }

        SearchConfig withFairnessOperator(boolean enabled) {
            return new SearchConfig(
                    initialAcceptanceProbability, finalTemperatureRatio, calibrationAttempts,
                    destroyRate, qMax, maxRepairAttempts, reactionFactor, segmentLength,
                    enabled);
        }

        int changedParameterCount() {
            SearchConfig baseline = baseline();
            int changes = 0;
            changes += Double.compare(
                    initialAcceptanceProbability, baseline.initialAcceptanceProbability) == 0 ? 0 : 1;
            changes += Double.compare(
                    finalTemperatureRatio, baseline.finalTemperatureRatio) == 0 ? 0 : 1;
            changes += calibrationAttempts == baseline.calibrationAttempts ? 0 : 1;
            changes += Double.compare(destroyRate, baseline.destroyRate) == 0 ? 0 : 1;
            changes += qMax == baseline.qMax ? 0 : 1;
            changes += maxRepairAttempts == baseline.maxRepairAttempts ? 0 : 1;
            changes += Double.compare(reactionFactor, baseline.reactionFactor) == 0 ? 0 : 1;
            changes += segmentLength == baseline.segmentLength ? 0 : 1;
            changes += fairnessOperator == baseline.fairnessOperator ? 0 : 1;
            return changes;
        }

        private static String decimalId(double value) {
            return Double.toString(value).replace('.', '_');
        }
    }

    record TuningCase(
            String dataset,
            String sha256,
            long seed,
            PlanningRequest request,
            PlanningProblem problem,
            RosterSolution warmStart) {

        String caseKey() {
            return dataset + "|" + sha256 + "|" + seed;
        }
    }

    record AlnsObservation(
            String recordType,
            String stage,
            String profile,
            String configId,
            String dataset,
            String inputSha256,
            long seed,
            RosterScore score,
            boolean feasible,
            long elapsedMillis,
            long evaluations,
            long bestEvaluation,
            String terminationReason,
            long rollbackAttempts,
            long rollbackFailures,
            long destroyFailures,
            long repairFailures,
            long operatorExceptions,
            long scoreMismatchFailures,
            long stateCorruptionFailures,
            double observedInitialWorseningAcceptanceRate,
            int calibrationEvaluatedCandidates,
            List<OperatorStatistics> destroyOperators,
            List<OperatorStatistics> repairOperators,
            String finalBestDestroy,
            String finalBestRepair) {

        String caseKey() {
            return dataset + "|" + inputSha256 + "|" + seed;
        }
    }

    record OptaObservation(
            String recordType,
            String profile,
            String dataset,
            String inputSha256,
            long seed,
            RosterScore score,
            boolean feasible,
            long elapsedMillis,
            long evaluations,
            long bestEvaluation) {

        String caseKey() {
            return dataset + "|" + inputSha256 + "|" + seed;
        }
    }

    record Ranking(
            String configId,
            long feasibleCount,
            long pairwiseWins,
            long pairwiseTies,
            long pairwiseLosses,
            long ordinalRankSum,
            int changedParameterCount,
            long elapsedP95Millis,
            long rollbackFailures,
            long globalBestCount) {
    }

    record StageResult(
            String stage,
            String partition,
            long evaluationLimit,
            Map<String, SearchConfig> configById,
            List<AlnsObservation> observations,
            List<Ranking> rankings) {
    }

    record ScoreQuantiles(RosterScore p10, RosterScore median, RosterScore p90) {
        String display() {
            return p10 + " / " + median + " / " + p90;
        }
    }

    record OperatorAggregate(
            String operatorId,
            long selections,
            long globalBest,
            long currentImprovement,
            long acceptedWorsening,
            long rejections,
            long finalBest) {
    }

    record ComparisonSummary(
            String configId,
            int pairCount,
            long optaFeasible,
            long candidateFeasible,
            long wins,
            long ties,
            long losses,
            ScoreQuantiles optaScores,
            ScoreQuantiles candidateScores,
            long[] deltaP10,
            long[] deltaMedian,
            long[] deltaP90,
            long optaElapsedP95,
            long candidateElapsedP95,
            long optaBestEvaluationP50,
            long optaBestEvaluationP90,
            long candidateBestEvaluationP50,
            long candidateBestEvaluationP90,
            long rollbackAttempts,
            long rollbackFailures,
            List<OperatorAggregate> destroyOperators,
            List<OperatorAggregate> repairOperators) {
    }

    record ComparisonResult(
            String profile,
            boolean crossedOrder,
            String orderNote,
            List<AlnsObservation> candidateRuns,
            List<OptaObservation> optaRuns,
            List<ComparisonSummary> summaries) {
    }

    private static final class MutableOperatorAggregate {
        private final String operatorId;
        private long selections;
        private long globalBest;
        private long currentImprovement;
        private long acceptedWorsening;
        private long rejections;
        private long finalBestCount;

        private MutableOperatorAggregate(String operatorId) {
            this.operatorId = operatorId;
        }

        private void add(OperatorStatistics statistics) {
            selections += statistics.selectionCount();
            globalBest += statistics.globalBestCount();
            currentImprovement += statistics.currentImprovementCount();
            acceptedWorsening += statistics.acceptedWorseningCount();
            rejections += statistics.rejectionCount();
        }

        private OperatorAggregate snapshot() {
            return new OperatorAggregate(
                    operatorId, selections, globalBest, currentImprovement,
                    acceptedWorsening, rejections, finalBestCount);
        }
    }

    private enum Profile {
        FIXED_EVALUATIONS("fixed-evaluations"),
        WALL_CLOCK("wall-clock");

        private final String id;

        Profile(String id) {
            this.id = id;
        }
    }

    record TuningConfig(
            List<String> datasets,
            List<Long> trainingSeeds,
            List<Long> validationSeeds,
            long trainingEvaluationLimit,
            long validationEvaluationLimit,
            int validationCandidateCount,
            int comparisonCandidateCount,
            long comparisonPojoEvaluationLimit,
            long optaPlannerEvaluationLimit,
            long wallClockSeconds,
            boolean warmup,
            Path outputDirectory,
            Path rawOutput,
            Path summaryOutput,
            Path reportOutput) {

        static TuningConfig fromSystemProperties() {
            List<String> datasets = csv(
                    System.getProperty("phase6.alns-tuning.datasets"),
                    DEFAULT_DATASETS);
            List<Long> trainingSeeds = csv(
                    System.getProperty("phase6.alns-tuning.training-seeds"),
                    List.of("201", "202")).stream().map(Long::parseLong).toList();
            List<Long> validationSeeds = csv(
                    System.getProperty("phase6.alns-tuning.validation-seeds"),
                    List.of("301", "302")).stream().map(Long::parseLong).toList();
            validatePartitions(trainingSeeds, validationSeeds);
            Path outputDirectory = Path.of(System.getProperty(
                    "phase6.alns-tuning.output-directory",
                    "benchmark-artifacts/phase6/tuning/manual"));
            int validationCandidates = integerProperty(
                    "phase6.alns-tuning.validation-candidates", 6);
            int comparisonCandidates = integerProperty(
                    "phase6.alns-tuning.comparison-candidates", 2);
            if (comparisonCandidates > validationCandidates) {
                throw new IllegalArgumentException(
                        "comparison-candidates는 validation-candidates 이하여야 합니다.");
            }
            return new TuningConfig(
                    datasets,
                    trainingSeeds,
                    validationSeeds,
                    longProperty("phase6.alns-tuning.training-evaluation-limit", 1_000L),
                    longProperty("phase6.alns-tuning.validation-evaluation-limit", 5_000L),
                    validationCandidates,
                    comparisonCandidates,
                    longProperty("phase6.alns-tuning.comparison-pojo-evaluation-limit", 5_000L),
                    longProperty("phase6.alns-tuning.optaplanner-evaluation-limit", 2_296_836L),
                    nonNegativeLongProperty("phase6.alns-tuning.wall-clock-seconds", 10L),
                    Boolean.parseBoolean(System.getProperty(
                            "phase6.alns-tuning.warmup", "true")),
                    outputDirectory,
                    outputDirectory.resolve("raw.jsonl"),
                    outputDirectory.resolve("summary.json"),
                    outputDirectory.resolve("report.md"));
        }

        TuningConfig withOptaPlannerEvaluationLimit(long value) {
            return new TuningConfig(
                    datasets, trainingSeeds, validationSeeds, trainingEvaluationLimit,
                    validationEvaluationLimit, validationCandidateCount,
                    comparisonCandidateCount, comparisonPojoEvaluationLimit, value,
                    wallClockSeconds, warmup, outputDirectory, rawOutput, summaryOutput,
                    reportOutput);
        }

        TuningConfig withWallClockSeconds(long value) {
            return new TuningConfig(
                    datasets, trainingSeeds, validationSeeds, trainingEvaluationLimit,
                    validationEvaluationLimit, validationCandidateCount,
                    comparisonCandidateCount, comparisonPojoEvaluationLimit,
                    optaPlannerEvaluationLimit, value, warmup, outputDirectory, rawOutput,
                    summaryOutput, reportOutput);
        }

        static void validatePartitions(
                List<Long> trainingSeeds, List<Long> validationSeeds) {
            if (trainingSeeds.isEmpty() || validationSeeds.isEmpty()) {
                throw new IllegalArgumentException("훈련/검증 seed는 비어 있을 수 없습니다.");
            }
            Set<Long> overlap = new LinkedHashSet<>(trainingSeeds);
            overlap.retainAll(validationSeeds);
            if (!overlap.isEmpty()) {
                throw new IllegalArgumentException("훈련/검증 seed가 겹칩니다: " + overlap);
            }
            Set<Long> locked = new LinkedHashSet<>(trainingSeeds);
            locked.addAll(validationSeeds);
            locked.retainAll(LOCKED_HOLDOUT_SEEDS);
            if (!locked.isEmpty()) {
                throw new IllegalArgumentException(
                        "잠긴 101..110 holdout seed는 튜닝에 사용할 수 없습니다: " + locked);
            }
        }

        private static int integerProperty(String name, int defaultValue) {
            int value = Integer.parseInt(System.getProperty(name, Integer.toString(defaultValue)));
            if (value <= 0) {
                throw new IllegalArgumentException(name + "은 양수여야 합니다.");
            }
            return value;
        }

        private static long longProperty(String name, long defaultValue) {
            long value = Long.parseLong(System.getProperty(name, Long.toString(defaultValue)));
            if (value <= 0L) {
                throw new IllegalArgumentException(name + "은 양수여야 합니다.");
            }
            return value;
        }

        private static long nonNegativeLongProperty(String name, long defaultValue) {
            long value = Long.parseLong(System.getProperty(name, Long.toString(defaultValue)));
            if (value < 0L) {
                throw new IllegalArgumentException(name + "은 0 이상이어야 합니다.");
            }
            return value;
        }

        private static List<String> csv(String value, List<String> defaults) {
            if (value == null || value.isBlank()) {
                return List.copyOf(defaults);
            }
            List<String> parsed = Arrays.stream(value.split(","))
                    .map(String::strip)
                    .filter(token -> !token.isEmpty())
                    .toList();
            if (parsed.isEmpty()) {
                throw new IllegalArgumentException("CSV 설정은 비어 있을 수 없습니다.");
            }
            return parsed;
        }
    }
}
