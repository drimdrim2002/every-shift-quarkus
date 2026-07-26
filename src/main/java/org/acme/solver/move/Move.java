package org.acme.solver.move;

import java.util.List;

/**
 * apply/undo가 정확한 역연산이어야 하는 원자적 move 계약입니다.
 */
public interface Move {

    String moveType();

    List<AssignmentChange> changes();

    void apply(SearchState state);

    void undo(SearchState state);
}
