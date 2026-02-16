package org.acme.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;

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
}
