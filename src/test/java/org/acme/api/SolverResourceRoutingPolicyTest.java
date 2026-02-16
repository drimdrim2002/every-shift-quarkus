package org.acme.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.acme.api.dto.PlanningRequest;
import org.acme.service.CloudRunJobInvoker;
import org.junit.jupiter.api.Test;

public class SolverResourceRoutingPolicyTest {

    @Test
    public void runLocallyIsEnabledOnlyInDevProfile() {
        SolverResource resource = new SolverResource();
        resource.runLocally = true;
        resource.activeProfile = "dev";

        assertTrue(resource.isLocalExecutionEnabled());
    }

    @Test
    public void runLocallyIsDisabledInNonDevProfile() {
        SolverResource resource = new SolverResource();
        resource.runLocally = true;
        resource.activeProfile = "prod";

        assertFalse(resource.isLocalExecutionEnabled());
    }

    @Test
    public void runLocallyFlagFalseAlwaysDisablesLocalExecution() {
        SolverResource resource = new SolverResource();
        resource.runLocally = false;
        resource.activeProfile = "dev";

        assertFalse(resource.isLocalExecutionEnabled());
    }

    @Test
    public void dispatchModeCloudRunJobCallsCloudRunInvoker() {
        TestSolverResource resource = new TestSolverResource();
        StubCloudRunJobInvoker invoker = new StubCloudRunJobInvoker();
        resource.dispatchMode = "CLOUD_RUN_JOB";
        resource.cloudRunJobInvoker = invoker;

        resource.dispatchAsyncExecution(null, "exec-1");

        assertTrue(invoker.called);
        assertFalse(resource.cloudTaskCalled);
    }

    @Test
    public void dispatchModeCloudTasksCallsCloudTaskDispatcher() {
        TestSolverResource resource = new TestSolverResource();
        StubCloudRunJobInvoker invoker = new StubCloudRunJobInvoker();
        resource.dispatchMode = "CLOUD_TASKS";
        resource.cloudRunJobInvoker = invoker;

        resource.dispatchAsyncExecution(null, "exec-2");

        assertFalse(invoker.called);
        assertTrue(resource.cloudTaskCalled);
    }

    private static final class TestSolverResource extends SolverResource {
        boolean cloudTaskCalled;

        @Override
        void createCloudTask(PlanningRequest requestDto, String executionId) {
            cloudTaskCalled = true;
        }
    }

    private static final class StubCloudRunJobInvoker extends CloudRunJobInvoker {
        boolean called;

        @Override
        public String runJob(String executionId, PlanningRequest requestDto) {
            called = true;
            return "projects/every-shift-api/locations/asia-northeast3/jobs/every-shift-job/executions/123";
        }
    }
}
