package org.acme.solver.shadow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.acme.solver.core.SolveOptions;

/** 잠긴 holdout 전에 고정하는 Phase 6 최종 POJO 후보와 호출 설정의 SHA-256 fingerprint입니다. */
public final class SolverConfigFingerprint {

    public static final String CANDIDATE_DESCRIPTOR = String.join("|",
            "candidate=ALNS_THEN_ORDERED_VND_PRECEPTOR_PREFIX_REASSIGN",
            "pipeline=ALNS:80,ORDERED_CHANGE_SWAP_VND:15,PRECEPTOR_PREFIX_REASSIGN:5",
            "prefixCandidateLimit=5400",
            "alnsDestroy=RandomRemoval,RelatedShiftRemoval,PreceptorRelationGroupRemoval",
            "alnsRepair=GreedyRepair,Regret2Repair,RelationAwareRepair",
            "destroyRate=0.05",
            "qMin=1",
            "qMax=8",
            "absoluteRemovalLimit=12",
            "maxRepairAttempts=2",
            "targetInitialAcceptance=0.2",
            "finalTemperatureRatio=0.01",
            "calibrationAttempts=64",
            "adaptive=1.0,0.1,0.2,100,10,5,1,0",
            "scoreOrder=hard,soft0,soft1,soft2,soft3",
            "candidateVersion=phase7-shadow-v1");

    private static final String CANDIDATE_FINGERPRINT = sha256(CANDIDATE_DESCRIPTOR);

    private SolverConfigFingerprint() {
    }

    public static String candidateFingerprint() {
        return CANDIDATE_FINGERPRINT;
    }

    public static String invocationFingerprint(SolverMode mode, SolveOptions options) {
        String descriptor = String.join("|",
                CANDIDATE_DESCRIPTOR,
                "mode=" + mode,
                "seed=" + options.randomSeed(),
                "spentNanos=" + options.spentLimit().map(java.time.Duration::toNanos).orElse(-1L),
                "hasDeadline=" + options.hasDeadline(),
                "maxEvaluations=" + options.maxEvaluations(),
                "maxIterations=" + options.maxIterations(),
                "maxStagnantEvaluations=" + options.maxStagnantEvaluations(),
                "warmStart=" + options.warmStart().isPresent());
        return sha256(descriptor);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", impossible);
        }
    }
}
