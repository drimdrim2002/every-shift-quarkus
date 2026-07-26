package org.acme.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.acme.solver.core.SolverEngine;
import org.acme.solver.shadow.ShadowSolverCoordinator;
import org.acme.solver.shadow.SolverMode;
import org.junit.jupiter.api.Test;

import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class SolverEngineSelectionTest {

    @Inject
    SolverEngine solverEngine;

    @Test
    void 기본_모드는_OPTAPLANNER_ONLY다() {
        ShadowSolverCoordinator coordinator =
                (ShadowSolverCoordinator) ClientProxy.unwrap(solverEngine);

        assertEquals(SolverMode.OPTAPLANNER_ONLY, coordinator.mode());
    }

    @Test
    void 네_명시적_모드만_선택할_수_있다() {
        SolverEngine opta = (problem, options, listener) -> null;
        SolverEngine pojo = (problem, options, listener) -> null;
        SolverEngineProducer producer = new SolverEngineProducer();

        for (SolverMode mode : SolverMode.values()) {
            ShadowSolverCoordinator selected =
                    (ShadowSolverCoordinator) producer.selectedEngine(mode.name(), opta, pojo);
            assertEquals(mode, selected.mode());
        }
        assertThrows(IllegalArgumentException.class,
                () -> producer.selectedEngine("OPTAPLANNER", opta, pojo));
        assertThrows(IllegalArgumentException.class,
                () -> producer.selectedEngine("POJO_ALNS", opta, pojo));
    }
}
