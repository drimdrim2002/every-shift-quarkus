package org.acme.solver;

import org.acme.solver.core.SolverEngine;
import org.acme.solver.lahc.AlnsChangeSwapVndHybridSolverEngine;
import org.acme.solver.optaplanner.OptaPlannerSolverEngine;
import org.acme.solver.shadow.ShadowSolverCoordinator;
import org.acme.solver.shadow.SolverMode;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * 설정된 엔진 구현을 기본 {@link SolverEngine} CDI bean으로 노출합니다.
 */
@ApplicationScoped
public class SolverEngineProducer {

    @Produces
    @ApplicationScoped
    SolverEngine selectedEngine(
            @ConfigProperty(name = "solver.engine", defaultValue = "OPTAPLANNER_ONLY") String engineName,
            OptaPlannerSolverEngine optaPlannerEngine) {
        SolverEngine pojoCandidate = new AlnsChangeSwapVndHybridSolverEngine(
                AlnsChangeSwapVndHybridSolverEngine.Mode
                        .ALNS_THEN_ORDERED_VND_WITH_PRECEPTOR_PREFIX_REASSIGN);
        return selectedEngine(engineName, optaPlannerEngine, pojoCandidate);
    }

    SolverEngine selectedEngine(
            String engineName,
            SolverEngine optaPlannerEngine,
            SolverEngine pojoCandidate) {
        return new ShadowSolverCoordinator(
                SolverMode.parse(engineName), optaPlannerEngine, pojoCandidate);
    }
}
