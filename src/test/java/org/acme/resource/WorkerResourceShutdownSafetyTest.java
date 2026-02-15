package org.acme.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.acme.api.dto.PlanningRequest;
import org.acme.model.EmployeeSchedule;
import org.acme.model.ExecutionStatus;
import org.acme.service.JobExecutionService;
import org.acme.solver.SolverRunner;
import org.junit.jupiter.api.Test;

class WorkerResourceShutdownSafetyTest {

    @Test
    void rethrowsOriginalSolverExceptionWhenSaveErrorFails() {
        WorkerResource resource = new WorkerResource();
        StubJobExecutionService jobExecutionService = new StubJobExecutionService();
        jobExecutionService.throwOnSaveError = true;

        resource.jobExecutionService = jobExecutionService;
        resource.solverRunner = new FailingSolverRunner();

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> resource.processEngineTask("execution-id", minimalValidRequest()));

        assertEquals("solver-failure", thrown.getMessage());
        assertTrue(jobExecutionService.saveErrorCalled);
    }

    @Test
    void skipsSaveErrorWhenExecutionIdMissing() {
        WorkerResource resource = new WorkerResource();
        StubJobExecutionService jobExecutionService = new StubJobExecutionService();

        resource.jobExecutionService = jobExecutionService;
        resource.solverRunner = new FailingSolverRunner();

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> resource.processEngineTask(null, minimalValidRequest()));

        assertEquals("solver-failure", thrown.getMessage());
        assertFalse(jobExecutionService.saveErrorCalled);
    }

    private PlanningRequest minimalValidRequest() {
        PlanningRequest.OrganizationInfo organization = new PlanningRequest.OrganizationInfo(
                "org-1",
                "org-name",
                "HOSPITAL",
                List.of(),
                null,
                0,
                null,
                0);

        return new PlanningRequest(
                organization,
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private static final class FailingSolverRunner extends SolverRunner {
        @Override
        public EmployeeSchedule solveIncremental(
                PlanningRequest request,
                String executionId,
                java.util.function.Consumer<EmployeeSchedule> intermediateCallback) {
            throw new RuntimeException("solver-failure");
        }
    }

    private static final class StubJobExecutionService extends JobExecutionService {
        boolean saveErrorCalled;
        boolean throwOnSaveError;

        @Override
        public void updateStatus(String id, ExecutionStatus status) {
            // no-op
        }

        @Override
        public void saveError(String id, String errorMessage) {
            saveErrorCalled = true;
            if (throwOnSaveError) {
                throw new RuntimeException("save-error-failure");
            }
        }
    }
}
