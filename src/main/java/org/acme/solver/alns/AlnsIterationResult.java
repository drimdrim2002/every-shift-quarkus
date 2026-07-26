package org.acme.solver.alns;

import org.acme.solver.core.RosterScore;

/** partial assignment를 포함하지 않는 ALNS iteration 결과입니다. */
public record AlnsIterationResult(
        AlnsIterationStatus status,
        String destroyOperatorId,
        String repairOperatorId,
        int requestedRemovalCount,
        int actualRemovalCount,
        int repairAttempts,
        int changedAssignmentCount,
        RosterScore currentScore,
        RosterScore candidateScore,
        String diagnosticCode) {
}
