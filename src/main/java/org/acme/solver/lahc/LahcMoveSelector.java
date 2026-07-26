package org.acme.solver.lahc;

import java.util.Optional;
import java.util.SplittableRandom;

import org.acme.solver.core.PlanningProblem;
import org.acme.solver.move.Move;
import org.acme.solver.move.SearchState;

/** no-op을 반환하지 않는 결정론적 LAHC 이웃 선택 경계입니다. */
@FunctionalInterface
public interface LahcMoveSelector {

    Optional<Move> select(PlanningProblem problem, SearchState state, SplittableRandom random);
}
