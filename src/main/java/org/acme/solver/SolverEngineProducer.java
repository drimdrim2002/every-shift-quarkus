package org.acme.solver;

import java.util.Locale;

import org.acme.solver.core.SolverEngine;
import org.acme.solver.alns.AlnsSolverEngine;
import org.acme.solver.lahc.LahcSolverEngine;
import org.acme.solver.optaplanner.OptaPlannerSolverEngine;
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
            @ConfigProperty(name = "solver.engine", defaultValue = "OPTAPLANNER") String engineName,
            OptaPlannerSolverEngine optaPlannerEngine,
            LahcSolverEngine lahcEngine,
            AlnsSolverEngine alnsEngine) {
        String normalized = engineName.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "OPTAPLANNER" -> optaPlannerEngine;
            case "POJO_LAHC" -> lahcEngine;
            case "POJO_ALNS" -> alnsEngine;
            default -> throw new IllegalArgumentException("지원하지 않는 solver.engine입니다: " + engineName);
        };
    }

    /** Phase 2/5 선택 테스트의 기존 호출 계약을 보존합니다. */
    SolverEngine selectedEngine(
            String engineName,
            OptaPlannerSolverEngine optaPlannerEngine,
            LahcSolverEngine lahcEngine) {
        return selectedEngine(engineName, optaPlannerEngine, lahcEngine, new AlnsSolverEngine());
    }
}
