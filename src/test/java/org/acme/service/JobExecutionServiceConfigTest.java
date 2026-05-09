package org.acme.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.optaplanner.core.api.score.buildin.bendable.BendableScore;

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
    void extractScoreFieldsMapsBusinessSoftScoresAfterNightPriorityLevels() {
        BendableScore score = BendableScore.of(
                new int[] { 0 },
                new int[] { -7, -30, -120, -5400, 240 });

        Map<String, Object> fields = JobExecutionService.extractScoreFields(score);

        assertEquals(0, fields.get("hardScore"));
        assertEquals(-7, fields.get("night48RestSoftScore"));
        assertEquals(-30, fields.get("night32RestSoftScore"));
        assertEquals(-120, fields.get("undesiredSoftScore"));
        assertEquals(-5400, fields.get("fairSoftScore"));
        assertEquals(240, fields.get("desiredSoftScore"));
    }
}
