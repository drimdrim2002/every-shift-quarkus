package org.acme.solver.alns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class OperatorCompatibilityMatrixTest {

    @Test
    void baseline_matrix는_일반_destroy와_세_repair를_허용한다() {
        OperatorCompatibilityMatrix matrix = OperatorCompatibilityMatrix.baseline();

        assertTrue(matrix.isCompatible(new RandomRemoval(), new GreedyRepair()));
        assertTrue(matrix.isCompatible(new RandomRemoval(), new Regret2Repair()));
        assertTrue(matrix.isCompatible(new RelatedShiftRemoval(), new RelationAwareRepair()));
    }

    @Test
    void relation_group_destroy는_relation_aware_repair만_허용한다() {
        OperatorCompatibilityMatrix matrix = OperatorCompatibilityMatrix.baseline();
        PreceptorRelationGroupRemoval destroy = new PreceptorRelationGroupRemoval();

        assertFalse(matrix.isCompatible(destroy, new GreedyRepair()));
        assertFalse(matrix.isCompatible(destroy, new Regret2Repair()));
        assertTrue(matrix.isCompatible(destroy, new RelationAwareRepair()));
        assertThrows(IllegalArgumentException.class,
                () -> matrix.requireCompatible(destroy, new GreedyRepair()));
        assertEquals(List.of(RelationAwareRepair.ID),
                matrix.compatibleRepairs(
                        destroy,
                        List.of(new GreedyRepair(), new Regret2Repair(), new RelationAwareRepair()))
                        .stream().map(RepairOperator::id).toList());
    }
}
