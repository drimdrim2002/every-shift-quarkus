package org.acme.solver.lahc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.acme.solver.core.PlanningProblem;
import org.acme.solver.move.SearchState;
import org.acme.solver.score.FullScoreCalculator;
import org.acme.solver.score.ScoreLevel;

/**
 * preceptor prefix-loss 진단에서 확인한 soft[0]/soft[1] hotspot을 우선하는 test-only reassign selector입니다.
 *
 * <p>순위는 현재 assignment를 한 번 full-score로 계산한 breakdown의 실제 기여 shift만 사용합니다.
 * soft[0](undesired) 기여 shift, soft[1](fairness) 기여 shift, 나머지 mutable shift 순이며,
 * 같은 순위에서는 shift/target stable index 오름차순으로 고정합니다. 후보 점수와 수락 여부는
 * 추측하지 않고 engine의 transaction full-score 비교에 맡깁니다.</p>
 */
public final class PreceptorPrefixGuidedProtectedReassignSelector {

    private final FullScoreCalculator full;
    private long rankingBuilds;
    private long rawCandidates;
    private long soft0HotspotCandidates;
    private long soft1HotspotCandidates;

    public PreceptorPrefixGuidedProtectedReassignSelector(FullScoreCalculator full) {
        this.full = Objects.requireNonNull(full, "full");
    }

    public List<Candidate> candidates(PlanningProblem problem, SearchState state) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(state, "state");
        Set<Integer> soft0Hotspots = new HashSet<>();
        Set<Integer> soft1Hotspots = new HashSet<>();
        full.calculateWithBreakdown(problem, state.snapshot()).contributions().forEach(contribution -> {
            if (contribution.level() == ScoreLevel.SOFT_0) {
                soft0Hotspots.addAll(contribution.shiftIndexes());
            } else if (contribution.level() == ScoreLevel.SOFT_1) {
                soft1Hotspots.addAll(contribution.shiftIndexes());
            }
        });
        rankingBuilds++;
        List<Candidate> candidates = new ArrayList<>();
        for (int shiftIndex : problem.mutableShiftIndexes()) {
            int source = state.employeeIndex(shiftIndex);
            int priority = soft0Hotspots.contains(shiftIndex) ? 0 : soft1Hotspots.contains(shiftIndex) ? 1 : 2;
            for (int target = 0; target < problem.employeeCount(); target++) {
                if (source == target) {
                    continue;
                }
                rawCandidates++;
                if (priority == 0) {
                    soft0HotspotCandidates++;
                } else if (priority == 1) {
                    soft1HotspotCandidates++;
                }
                candidates.add(new Candidate(shiftIndex, target, priority));
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::priority)
                .thenComparingInt(Candidate::shiftIndex)
                .thenComparingInt(Candidate::targetEmployee));
        return List.copyOf(candidates);
    }

    public Metrics metrics() {
        return new Metrics(rankingBuilds, rawCandidates, soft0HotspotCandidates, soft1HotspotCandidates);
    }

    public record Candidate(int shiftIndex, int targetEmployee, int priority) {
    }

    public record Metrics(
            long rankingBuilds,
            long rawCandidates,
            long soft0HotspotCandidates,
            long soft1HotspotCandidates) {
        public Metrics {
            if (rankingBuilds < 0L || rawCandidates < 0L || soft0HotspotCandidates < 0L
                    || soft1HotspotCandidates < 0L) {
                throw new IllegalArgumentException("preceptor prefix selector metric은 음수일 수 없습니다.");
            }
        }
    }
}
