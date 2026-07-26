package org.acme.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Optional;

import org.acme.solver.core.RosterScore;
import org.junit.jupiter.api.Test;

class JobExecutionServiceConfigTest {

    @Test
    void initFallsBackToDefaultWhenPropertyMissing() {
        JobExecutionService service = new JobExecutionService();
        service.configuredCollectionName = Optional.empty();

        service.init();

        assertEquals("job-executions", service.collectionName);
    }

    @Test
    void initFallsBackToDefaultWhenPropertyBlank() {
        JobExecutionService service = new JobExecutionService();
        service.configuredCollectionName = Optional.of("   ");

        service.init();

        assertEquals("job-executions", service.collectionName);
    }

    @Test
    void initUsesTrimmedCollectionNameWhenProvided() {
        JobExecutionService service = new JobExecutionService();
        service.configuredCollectionName = Optional.of(" custom-jobs ");

        service.init();

        assertEquals("custom-jobs", service.collectionName);
    }

    @Test
    void extractScoreFieldsPreservesCurrentBusinessAndLegacyAliases() {
        RosterScore score = RosterScore.of(0, -30, -120, -5409, 240);

        Map<String, Object> fields = JobExecutionService.extractScoreFields(score);

        assertEquals(0, fields.get("hardScore"));
        assertEquals(0, fields.get("night48RestSoftScore"));
        assertEquals(-30, fields.get("night32RestSoftScore"));
        assertEquals(-120, fields.get("undesiredSoftScore"));
        assertEquals(0, fields.get("threeConsecutiveNightSoftScore"));
        assertEquals(-5409, fields.get("fairnessSoftScore"));
        assertEquals(240, fields.get("desiredSoftScore"));
        assertEquals(-5409, fields.get("burdenFairnessSoftScore"));
        assertEquals(-5409, fields.get("fairSoftScore"));
    }
}
