package org.acme.solver.alns;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.random.RandomGenerator;

import org.acme.solver.core.PlanningProblem;
import org.acme.solver.core.RosterScore;
import org.acme.solver.core.RosterSolution;
import org.acme.solver.move.MoveCancelledException;
import org.acme.solver.move.MoveTransaction;
import org.acme.solver.move.ReassignMove;
import org.acme.solver.move.SearchState;
import org.acme.solver.move.StateCorruptionException;
import org.acme.solver.score.IncrementalScoreCalculator;
import org.acme.solver.score.ScoreMismatchException;

/**
 * Phase 6A의 안전한 destroy -> partial -> repair -> complete candidate 한 iteration입니다.
 * adaptive/SA/best callback은 이 계층에 포함하지 않습니다.
 */
public final class AlnsIteration {

    private final PlanningProblem problem;
    private final SearchState state;
    private final IncrementalScoreCalculator incrementalScoreCalculator;
    private final OperatorCompatibilityMatrix compatibilityMatrix;
    private final DestroySizePolicy destroySizePolicy;

    public AlnsIteration(
            PlanningProblem problem,
            SearchState state,
            IncrementalScoreCalculator incrementalScoreCalculator,
            OperatorCompatibilityMatrix compatibilityMatrix) {
        this.problem = Objects.requireNonNull(problem, "problem");
        this.state = Objects.requireNonNull(state, "state");
        this.incrementalScoreCalculator = Objects.requireNonNull(
                incrementalScoreCalculator, "incrementalScoreCalculator");
        this.compatibilityMatrix = Objects.requireNonNull(compatibilityMatrix, "compatibilityMatrix");
        if (state.problem() != problem || incrementalScoreCalculator.problem() != problem) {
            throw new IllegalArgumentException("ALNS iteration 구성요소는 같은 PlanningProblem을 사용해야 합니다.");
        }
        this.destroySizePolicy = new DestroySizePolicy();
    }

    public AlnsIterationResult execute(
            DestroyOperator destroyOperator,
            RepairOperator repairOperator,
            AcceptancePolicy acceptancePolicy,
            AlnsIterationConfig config,
            RandomGenerator random,
            BooleanSupplier cancellationToken) {
        Objects.requireNonNull(destroyOperator, "destroyOperator");
        Objects.requireNonNull(repairOperator, "repairOperator");
        Objects.requireNonNull(acceptancePolicy, "acceptancePolicy");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(random, "random");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        compatibilityMatrix.requireCompatible(destroyOperator, repairOperator);

        RosterScore baselineScore = state.score();
        int[] baselineAssignments = state.assignments();
        DestroySize destroySize = destroySizePolicy.calculate(
                problem.mutableShiftIndexes().length,
                config.destroyRate(),
                config.qMin(),
                config.qMax(),
                config.absoluteRemovalLimit());
        if (cancellationToken.getAsBoolean()) {
            return result(AlnsIterationStatus.CANCELLED, destroyOperator, repairOperator,
                    destroySize, 0, 0, 0, baselineScore, null, "cancelled:before-transaction");
        }
        if (destroySize.requestedRemovalCount() == 0) {
            return result(AlnsIterationStatus.NO_MUTABLE_SHIFT, destroyOperator, repairOperator,
                    destroySize, 0, 0, 0, baselineScore, null, null);
        }

        int actualRemovalCount = 0;
        int repairAttempts = 0;
        int changedAssignmentCount = 0;
        String stage = "destroy";
        try (MoveTransaction transaction = MoveTransaction.open(state, incrementalScoreCalculator)) {
            try {
                DestroyPlan destroyPlan;
                DestroyContext destroyContext = new DestroyContext(
                        problem, baselineAssignments, destroySize, random);
                try {
                    destroyPlan = Objects.requireNonNull(
                            destroyOperator.destroy(destroyContext),
                            "destroy operator가 null plan을 반환했습니다.");
                    validateDestroyPlan(destroyPlan, destroySize);
                } finally {
                    destroyContext.close();
                }
                int[] removedShiftIndexes = destroyPlan.shiftIndexes();
                actualRemovalCount = removedShiftIndexes.length;
                transaction.throwIfCancelled(cancellationToken);

                stage = "repair";
                PartialSolution partial = new PartialSolution(
                        problem.employeeCount(), baselineAssignments, removedShiftIndexes);
                RepairContext repairContext = new RepairContext(
                        problem, partial, random, config.maxRepairAttempts());
                RepairResult repairResult;
                int[] candidateAssignments = null;
                try {
                    repairResult = Objects.requireNonNull(
                            repairOperator.repair(repairContext),
                            "repair operator가 null result를 반환했습니다.");
                    repairAttempts = repairContext.attempts();
                    boolean validResult = repairResult.attempts() == repairAttempts
                            && repairAttempts <= config.maxRepairAttempts()
                            && repairResult.complete() == repairContext.isComplete();
                    if (!validResult || !repairResult.complete()) {
                        transaction.rollback();
                        return result(AlnsIterationStatus.REPAIR_FAILED,
                                destroyOperator, repairOperator, destroySize,
                                actualRemovalCount, repairAttempts, 0,
                                baselineScore, null, "repair:incomplete");
                    }
                    candidateAssignments = repairContext.completeAssignments();
                } finally {
                    repairAttempts = repairContext.attempts();
                    repairContext.close();
                }
                transaction.throwIfCancelled(cancellationToken);

                stage = "candidate-apply";
                for (int shiftIndex : removedShiftIndexes) {
                    int newEmployeeIndex = candidateAssignments[shiftIndex];
                    if (newEmployeeIndex == baselineAssignments[shiftIndex]) {
                        continue;
                    }
                    transaction.throwIfCancelled(cancellationToken);
                    transaction.apply(ReassignMove.create(
                            problem, state, shiftIndex, newEmployeeIndex));
                    changedAssignmentCount++;
                }

                stage = "candidate-verify";
                RosterSolution verifiedCandidate = transaction.verifyCandidate();
                RosterScore candidateScore = verifiedCandidate.score();

                stage = "acceptance";
                if (acceptancePolicy.accept(baselineScore, candidateScore)) {
                    transaction.commit();
                    return result(AlnsIterationStatus.ACCEPTED,
                            destroyOperator, repairOperator, destroySize,
                            actualRemovalCount, repairAttempts, changedAssignmentCount,
                            candidateScore, candidateScore, null);
                }
                transaction.rollback();
                return result(AlnsIterationStatus.REJECTED,
                        destroyOperator, repairOperator, destroySize,
                        actualRemovalCount, repairAttempts, changedAssignmentCount,
                        baselineScore, candidateScore, null);
            } catch (DestroyPlanValidationException invalidPlan) {
                return result(AlnsIterationStatus.DESTROY_FAILED,
                        destroyOperator, repairOperator, destroySize,
                        actualRemovalCount, repairAttempts, changedAssignmentCount,
                        baselineScore, null, "destroy:invalid-plan");
            } catch (MoveCancelledException cancelled) {
                return result(AlnsIterationStatus.CANCELLED,
                        destroyOperator, repairOperator, destroySize,
                        actualRemovalCount, repairAttempts, changedAssignmentCount,
                        baselineScore, null, "cancelled:" + stage);
            } catch (ScoreMismatchException | StateCorruptionException fatal) {
                throw fatal;
            } catch (RuntimeException operatorFailure) {
                return result(AlnsIterationStatus.OPERATOR_EXCEPTION,
                        destroyOperator, repairOperator, destroySize,
                        actualRemovalCount, repairAttempts, changedAssignmentCount,
                        baselineScore, null,
                        stage + ":" + operatorFailure.getClass().getSimpleName());
            }
        }
    }

    private void validateDestroyPlan(DestroyPlan plan, DestroySize destroySize) {
        int[] shiftIndexes = plan.shiftIndexes();
        if (shiftIndexes.length < destroySize.requestedRemovalCount()
                || shiftIndexes.length > destroySize.actualRemovalLimit()) {
            throw new DestroyPlanValidationException();
        }
        Set<Integer> seen = new HashSet<>();
        for (int shiftIndex : shiftIndexes) {
            if (shiftIndex < 0
                    || shiftIndex >= problem.shiftCount()
                    || !problem.isMutableShift(shiftIndex)
                    || !seen.add(shiftIndex)) {
                throw new DestroyPlanValidationException();
            }
        }
    }

    private static AlnsIterationResult result(
            AlnsIterationStatus status,
            DestroyOperator destroyOperator,
            RepairOperator repairOperator,
            DestroySize destroySize,
            int actualRemovalCount,
            int repairAttempts,
            int changedAssignmentCount,
            RosterScore currentScore,
            RosterScore candidateScore,
            String diagnosticCode) {
        return new AlnsIterationResult(
                status,
                destroyOperator.id(),
                repairOperator.id(),
                destroySize.requestedRemovalCount(),
                actualRemovalCount,
                repairAttempts,
                changedAssignmentCount,
                currentScore,
                candidateScore,
                diagnosticCode);
    }

    private static final class DestroyPlanValidationException extends RuntimeException {
    }
}
