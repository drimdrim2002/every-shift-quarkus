package org.acme.solver;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.acme.solver.core.SolverEngine;
import org.acme.solver.alns.AlnsSolverEngine;
import org.acme.solver.lahc.LahcSolverEngine;
import org.acme.solver.optaplanner.OptaPlannerSolverEngine;
import org.junit.jupiter.api.Test;

import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class SolverEngineSelectionTest {

    @Inject
    SolverEngine solverEngine;

    @Test
    void 기본_엔진은_OPTAPLANNER다() {
        assertInstanceOf(OptaPlannerSolverEngine.class, ClientProxy.unwrap(solverEngine));
    }

    @Test
    void POJO_LAHC_설정은_LAHC_엔진을_선택한다() {
        OptaPlannerSolverEngine optaPlanner = new OptaPlannerSolverEngine();
        LahcSolverEngine lahc = new LahcSolverEngine();

        SolverEngine selected = new SolverEngineProducer().selectedEngine(
                "pojo_lahc", optaPlanner, lahc);

        assertSame(lahc, selected);
    }

    @Test
    void POJO_ALNS_설정은_ALNS_엔진을_선택한다() {
        OptaPlannerSolverEngine optaPlanner = new OptaPlannerSolverEngine();
        LahcSolverEngine lahc = new LahcSolverEngine();
        AlnsSolverEngine alns = new AlnsSolverEngine();

        SolverEngine selected = new SolverEngineProducer().selectedEngine(
                "pojo_alns", optaPlanner, lahc, alns);

        assertSame(alns, selected);
    }
}
