package org.acme.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.acme.solver.core.SolverEngine;
import org.acme.solver.lahc.AlnsChangeSwapVndHybridSolverEngine;
import org.junit.jupiter.api.Test;

import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class SolverEngineSelectionTest {

    @Inject
    SolverEngine solverEngine;

    @Test
    void 단일_POJO_엔진만_CDI로_선택된다() {
        SolverEngine selected = (SolverEngine) ClientProxy.unwrap(solverEngine);
        assertEquals(AlnsChangeSwapVndHybridSolverEngine.class, selected.getClass());
    }

    @Test
    void producer도_검증된_POJO_후보를_직접_반환한다() {
        assertEquals(
                AlnsChangeSwapVndHybridSolverEngine.class,
                new SolverEngineProducer().selectedEngine().getClass());
    }
}
