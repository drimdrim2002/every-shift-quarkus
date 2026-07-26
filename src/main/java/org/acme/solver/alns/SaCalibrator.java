package org.acme.solver.alns;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.function.BooleanSupplier;

import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.move.SearchState;
import org.acme.solver.score.FullScoreCalculator;
import org.acme.solver.score.IncrementalScoreCalculator;

/** 파생 seed와 복제 state로 본 탐색을 소비하지 않는 scale 보정기입니다. */
public final class SaCalibrator {

    private static final long CALIBRATION_SEED_SALT = 0x53415F43414C4942L;

    private final FullScoreCalculator fullScoreCalculator;

    public SaCalibrator(FullScoreCalculator fullScoreCalculator) {
        this.fullScoreCalculator = Objects.requireNonNull(fullScoreCalculator, "fullScoreCalculator");
    }

    public SaCalibrationResult calibrate(
            PlanningProblem problem,
            RosterSolution seedSolution,
            List<? extends DestroyOperator> destroyOperators,
            List<? extends RepairOperator> repairOperators,
            OperatorCompatibilityMatrix compatibilityMatrix,
            AlnsIterationConfig iterationConfig,
            int attemptBudget,
            long searchSeed,
            double targetInitialAcceptanceProbability,
            BooleanSupplier interrupted) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(seedSolution, "seedSolution");
        Objects.requireNonNull(destroyOperators, "destroyOperators");
        Objects.requireNonNull(repairOperators, "repairOperators");
        Objects.requireNonNull(compatibilityMatrix, "compatibilityMatrix");
        Objects.requireNonNull(iterationConfig, "iterationConfig");
        Objects.requireNonNull(interrupted, "interrupted");
        if (attemptBudget < 0) {
            throw new IllegalArgumentException("calibration attemptBudget은 음수일 수 없습니다.");
        }
        if (!Double.isFinite(targetInitialAcceptanceProbability)
                || targetInitialAcceptanceProbability <= 0.0d
                || targetInitialAcceptanceProbability >= 1.0d) {
            throw new IllegalArgumentException("targetInitialAcceptanceProbability는 0과 1 사이여야 합니다.");
        }

        long derivedSeed = deriveSeed(searchSeed);
        if (attemptBudget == 0) {
            return SaCalibrationResult.fallback(derivedSeed, targetInitialAcceptanceProbability);
        }

        List<OperatorPair> pairs = compatiblePairs(
                destroyOperators, repairOperators, compatibilityMatrix);
        if (pairs.isEmpty()) {
            throw new IllegalArgumentException("calibration에 사용할 compatible operator 조합이 없습니다.");
        }

        RosterScore verifiedScore = fullScoreCalculator.calculateScore(problem, seedSolution);
        RosterSolution copiedSolution = new RosterSolution(
                problem.employeeCount(), seedSolution.employeeIndexByShift(), verifiedScore);
        SearchState copiedState = new SearchState(problem, copiedSolution);
        IncrementalScoreCalculator copiedIncremental = new IncrementalScoreCalculator(
                problem, copiedSolution, fullScoreCalculator);
        AlnsIteration iteration = new AlnsIteration(
                problem, copiedState, copiedIncremental, compatibilityMatrix);
        SplittableRandom calibrationRandom = new SplittableRandom(derivedSeed);

        List<Long> hardLosses = new ArrayList<>();
        List<List<Long>> softLosses = new ArrayList<>(RosterScore.SOFT_LEVELS);
        for (int level = 0; level < RosterScore.SOFT_LEVELS; level++) {
            softLosses.add(new ArrayList<>());
        }

        int attempts = 0;
        int evaluated = 0;
        while (attempts < attemptBudget && !interrupted.getAsBoolean()) {
            OperatorPair pair = pairs.get(calibrationRandom.nextInt(pairs.size()));
            RosterScore current = copiedState.score();
            AlnsIterationResult result = iteration.execute(
                    pair.destroy(),
                    pair.repair(),
                    RejectAllAcceptance.INSTANCE,
                    iterationConfig,
                    calibrationRandom,
                    interrupted);
            attempts++;
            RosterScore candidate = result.candidateScore();
            if (candidate == null) {
                continue;
            }
            evaluated++;
            collectWorseningDelta(current, candidate, hardLosses, softLosses);
        }

        double hardScale = medianOrOne(hardLosses);
        double[] softScales = new double[RosterScore.SOFT_LEVELS];
        int[] softCounts = new int[RosterScore.SOFT_LEVELS];
        double[] initialRates = new double[RosterScore.SOFT_LEVELS + 1];
        double initialTemperature = -1.0d / Math.log(targetInitialAcceptanceProbability);
        initialRates[0] = estimatedAcceptanceRate(
                hardLosses, hardScale, initialTemperature, targetInitialAcceptanceProbability);
        for (int level = 0; level < RosterScore.SOFT_LEVELS; level++) {
            List<Long> losses = softLosses.get(level);
            softScales[level] = medianOrOne(losses);
            softCounts[level] = losses.size();
            initialRates[level + 1] = estimatedAcceptanceRate(
                    losses,
                    softScales[level],
                    initialTemperature,
                    targetInitialAcceptanceProbability);
        }

        return new SaCalibrationResult(
                derivedSeed,
                attempts,
                evaluated,
                hardScale,
                softScales,
                hardLosses.size(),
                softCounts,
                initialRates);
    }

    public static long deriveSeed(long searchSeed) {
        return mix64(searchSeed ^ CALIBRATION_SEED_SALT);
    }

    static double medianOrOne(List<Long> positiveSamples) {
        Objects.requireNonNull(positiveSamples, "positiveSamples");
        if (positiveSamples.isEmpty()) {
            return 1.0d;
        }
        List<Long> sorted = new ArrayList<>(positiveSamples.size());
        for (Long sample : positiveSamples) {
            if (sample == null || sample <= 0L) {
                throw new IllegalArgumentException("scale 표본은 양수여야 합니다: " + sample);
            }
            sorted.add(sample);
        }
        Collections.sort(sorted);
        return sorted.get((sorted.size() - 1) / 2).doubleValue();
    }

    private static void collectWorseningDelta(
            RosterScore current,
            RosterScore candidate,
            List<Long> hardLosses,
            List<List<Long>> softLosses) {
        long hardDelta = candidate.hardDeltaFrom(current);
        if (hardDelta < 0L) {
            hardLosses.add(-hardDelta);
            return;
        }
        if (hardDelta > 0L) {
            return;
        }
        for (int level = 0; level < RosterScore.SOFT_LEVELS; level++) {
            long softDelta = candidate.softDeltaFrom(current, level);
            if (softDelta < 0L) {
                softLosses.get(level).add(-softDelta);
                return;
            }
            if (softDelta > 0L) {
                return;
            }
        }
    }

    private static List<OperatorPair> compatiblePairs(
            List<? extends DestroyOperator> destroys,
            List<? extends RepairOperator> repairs,
            OperatorCompatibilityMatrix matrix) {
        List<OperatorPair> result = new ArrayList<>();
        for (DestroyOperator destroy : destroys) {
            Objects.requireNonNull(destroy, "destroyOperator");
            for (RepairOperator repair : repairs) {
                Objects.requireNonNull(repair, "repairOperator");
                if (matrix.isCompatible(destroy, repair)) {
                    result.add(new OperatorPair(destroy, repair));
                }
            }
        }
        return List.copyOf(result);
    }

    private static double estimatedAcceptanceRate(
            List<Long> losses,
            double scale,
            double initialTemperature,
            double fallback) {
        if (losses.isEmpty()) {
            return fallback;
        }
        double sum = 0.0d;
        for (long loss : losses) {
            sum += Math.exp(-((double) loss / scale) / initialTemperature);
        }
        double result = sum / losses.size();
        if (!Double.isFinite(result) || result < 0.0d || result > 1.0d) {
            throw new IllegalStateException("calibration 초기 수락률이 올바르지 않습니다: " + result);
        }
        return result;
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private record OperatorPair(DestroyOperator destroy, RepairOperator repair) {
    }

    private enum RejectAllAcceptance implements AcceptancePolicy {
        INSTANCE;

        @Override
        public String id() {
            return "CALIBRATION_REJECT_ALL";
        }

        @Override
        public boolean accept(RosterScore currentScore, RosterScore candidateScore) {
            return false;
        }
    }
}
