package org.acme.solver.shadow;

import java.util.Locale;

/** Phase 7에서 명시적으로 허용하는 반환/관측 엔진 조합입니다. */
public enum SolverMode {
    OPTAPLANNER_ONLY(EngineRole.OPTAPLANNER, null),
    OPTAPLANNER_PRIMARY_SHADOW_POJO(EngineRole.OPTAPLANNER, EngineRole.POJO),
    POJO_PRIMARY_SHADOW_OPTAPLANNER(EngineRole.POJO, EngineRole.OPTAPLANNER),
    POJO_ONLY(EngineRole.POJO, null);

    private final EngineRole primary;
    private final EngineRole shadow;

    SolverMode(EngineRole primary, EngineRole shadow) {
        this.primary = primary;
        this.shadow = shadow;
    }

    public EngineRole primary() {
        return primary;
    }

    public EngineRole shadow() {
        return shadow;
    }

    public boolean hasShadow() {
        return shadow != null;
    }

    public static SolverMode parse(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException("solver.engine은 비어 있을 수 없습니다.");
        }
        try {
            return valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unsupported) {
            throw new IllegalArgumentException(
                    "지원하지 않는 solver.engine입니다. 허용값="
                            + java.util.Arrays.toString(values()) + ", actual=" + configured,
                    unsupported);
        }
    }

    public enum EngineRole {
        OPTAPLANNER,
        POJO
    }
}
