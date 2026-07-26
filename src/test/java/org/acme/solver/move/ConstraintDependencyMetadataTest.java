package org.acme.solver.move;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.YearMonth;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.acme.solver.score.DependencyDimension;
import org.junit.jupiter.api.Test;

class ConstraintDependencyMetadataTest {

    @Test
    void 모든_evaluator는_고유한_dependency_metadata를_가진다() {
        Phase4TestSupport.Fixture fixture = Phase4TestSupport.fixture("preceptor.json");

        assertEquals(12, fixture.incremental().dependencyMetadata().size());
        assertEquals(12, fixture.incremental().dependencyMetadata().stream()
                .map(metadata -> metadata.evaluatorId())
                .collect(Collectors.toSet()).size());
        assertTrue(fixture.incremental().dependencyMetadata().stream()
                .allMatch(metadata -> metadata.dimensions()
                        .contains(DependencyDimension.IMMUTABLE_SHIFT_INDEX)));
    }

    @Test
    void move_impact는_old_new직원_날짜_논리일_휴식월_relation_fairness_immutable을_포함한다() {
        Phase4TestSupport.Fixture fixture = Phase4TestSupport.fixture("preceptor.json");
        int shift = Phase4TestSupport.mutableShift(fixture.problem());
        int oldEmployee = fixture.state().employeeIndex(shift);
        int newEmployee = Phase4TestSupport.differentEmployee(fixture.problem(), oldEmployee, 0);
        ReassignMove move = ReassignMove.create(
                fixture.problem(), fixture.state(), shift, newEmployee);
        MoveImpact impact = new MoveImpactResolver(
                fixture.problem(), fixture.incremental().dependencyMetadata()).resolve(move);

        assertArrayEquals(new int[] { oldEmployee }, impact.oldEmployeeIndexes());
        assertArrayEquals(new int[] { newEmployee }, impact.newEmployeeIndexes());
        assertTrue(asSet(impact.affectedEmployeeIndexes()).containsAll(Set.of(oldEmployee, newEmployee)));
        assertTrue(asSet(impact.relationEmployeeIndexes()).containsAll(Set.of(oldEmployee, newEmployee)));
        assertArrayEquals(new int[] { shift }, impact.mutabilityCheckedShiftIndexes());
        assertTrue(impact.actualDates().contains(fixture.problem().shifts().get(shift).start().toLocalDate()));
        assertTrue(impact.logicalDates().contains(fixture.problem().shifts().get(shift).logicalDate()));
        assertTrue(impact.actualMonths().contains(YearMonth.from(fixture.problem().shifts().get(shift).start())));
        assertFalse(impact.restWindows().isEmpty());
        assertTrue(impact.dependencyDimensions().containsAll(Set.of(
                DependencyDimension.EMPLOYEE,
                DependencyDimension.ACTUAL_DATE,
                DependencyDimension.LOGICAL_DATE,
                DependencyDimension.ADJACENT_REST_WINDOW,
                DependencyDimension.ACTUAL_MONTH,
                DependencyDimension.PRECEPTOR_RELATION_GROUP,
                DependencyDimension.FAIRNESS_AGGREGATE,
                DependencyDimension.IMMUTABLE_SHIFT_INDEX)));
        assertArrayEquals(
                impact.affectedEmployeeIndexes(),
                impact.forEvaluator("fairness").evaluationUnitIndexes());
    }

    private static Set<Integer> asSet(int[] values) {
        return Arrays.stream(values).boxed().collect(Collectors.toSet());
    }
}
