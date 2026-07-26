package org.acme.solver.core;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * 엔진 공통 실행 옵션입니다. deadline은 {@link System#nanoTime()} 기준 단조 시간입니다.
 */
public final class SolveOptions {

    public static final long UNLIMITED = -1L;
    public static final long NO_DEADLINE = Long.MAX_VALUE;

    @FunctionalInterface
    public interface CancellationToken {
        CancellationToken NONE = () -> false;

        boolean isCancellationRequested();
    }

    private final Duration spentLimit;
    private final long deadlineNanos;
    private final long maxEvaluations;
    private final long maxIterations;
    private final long maxStagnantEvaluations;
    private final long randomSeed;
    private final RosterSolution warmStart;
    private final CancellationToken cancellationToken;

    private SolveOptions(Builder builder) {
        this.spentLimit = builder.spentLimit;
        this.deadlineNanos = builder.deadlineNanos;
        this.maxEvaluations = validateLimit(builder.maxEvaluations, "maxEvaluations");
        this.maxIterations = validateLimit(builder.maxIterations, "maxIterations");
        this.maxStagnantEvaluations = validateLimit(
                builder.maxStagnantEvaluations,
                "maxStagnantEvaluations");
        this.randomSeed = builder.randomSeed;
        this.warmStart = builder.warmStart;
        this.cancellationToken = Objects.requireNonNull(builder.cancellationToken, "cancellationToken");

        if (spentLimit != null && (spentLimit.isZero() || spentLimit.isNegative())) {
            throw new IllegalArgumentException("spentLimit은 양수여야 합니다: " + spentLimit);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .spentLimit(spentLimit)
                .deadlineNanos(deadlineNanos)
                .maxEvaluations(maxEvaluations)
                .maxIterations(maxIterations)
                .maxStagnantEvaluations(maxStagnantEvaluations)
                .randomSeed(randomSeed)
                .warmStart(warmStart)
                .cancellationToken(cancellationToken);
    }

    public Optional<Duration> spentLimit() {
        return Optional.ofNullable(spentLimit);
    }

    public long deadlineNanos() {
        return deadlineNanos;
    }

    public boolean hasDeadline() {
        return deadlineNanos != NO_DEADLINE;
    }

    public boolean isDeadlineReached() {
        return hasDeadline() && System.nanoTime() - deadlineNanos >= 0L;
    }

    public Duration remainingUntilDeadline() {
        if (!hasDeadline()) {
            throw new IllegalStateException("deadline이 설정되지 않았습니다.");
        }
        long remaining = deadlineNanos - System.nanoTime();
        return remaining <= 0L ? Duration.ZERO : Duration.ofNanos(remaining);
    }

    public long maxEvaluations() {
        return maxEvaluations;
    }

    public long maxIterations() {
        return maxIterations;
    }

    public long maxStagnantEvaluations() {
        return maxStagnantEvaluations;
    }

    public long randomSeed() {
        return randomSeed;
    }

    public Optional<RosterSolution> warmStart() {
        return Optional.ofNullable(warmStart);
    }

    public CancellationToken cancellationToken() {
        return cancellationToken;
    }

    private static long validateLimit(long value, String fieldName) {
        if (value == 0L || value < UNLIMITED) {
            throw new IllegalArgumentException(fieldName + "는 양수 또는 UNLIMITED(-1)여야 합니다: " + value);
        }
        return value;
    }

    public static final class Builder {

        private Duration spentLimit;
        private long deadlineNanos = NO_DEADLINE;
        private long maxEvaluations = UNLIMITED;
        private long maxIterations = UNLIMITED;
        private long maxStagnantEvaluations = UNLIMITED;
        private long randomSeed;
        private RosterSolution warmStart;
        private CancellationToken cancellationToken = CancellationToken.NONE;

        private Builder() {
        }

        public Builder spentLimit(Duration spentLimit) {
            this.spentLimit = spentLimit;
            return this;
        }

        public Builder deadlineNanos(long deadlineNanos) {
            this.deadlineNanos = deadlineNanos;
            return this;
        }

        public Builder maxEvaluations(long maxEvaluations) {
            this.maxEvaluations = maxEvaluations;
            return this;
        }

        public Builder maxIterations(long maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        public Builder maxStagnantEvaluations(long maxStagnantEvaluations) {
            this.maxStagnantEvaluations = maxStagnantEvaluations;
            return this;
        }

        public Builder randomSeed(long randomSeed) {
            this.randomSeed = randomSeed;
            return this;
        }

        public Builder warmStart(RosterSolution warmStart) {
            this.warmStart = warmStart;
            return this;
        }

        public Builder cancellationToken(CancellationToken cancellationToken) {
            this.cancellationToken = cancellationToken;
            return this;
        }

        public SolveOptions build() {
            return new SolveOptions(this);
        }
    }
}
