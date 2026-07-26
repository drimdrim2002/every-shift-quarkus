package org.acme.solver;

import org.acme.solver.core.SolverEngine;
import org.acme.solver.lahc.AlnsChangeSwapVndHybridSolverEngine;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * 설정된 엔진 구현을 기본 {@link SolverEngine} CDI bean으로 노출합니다.
 */
@ApplicationScoped
public class SolverEngineProducer {

    @Produces
    @ApplicationScoped
    SolverEngine selectedEngine() {
        return new AlnsChangeSwapVndHybridSolverEngine(
                AlnsChangeSwapVndHybridSolverEngine.Mode
                        .ALNS_THEN_ORDERED_VND_WITH_PRECEPTOR_PREFIX_REASSIGN);
    }
}
