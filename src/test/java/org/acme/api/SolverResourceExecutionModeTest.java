package org.acme.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.acme.api.dto.PlanningRequest;
import org.acme.service.JobExecutionService;
import org.junit.jupiter.api.Test;

import io.quarkus.runtime.LaunchMode;

class SolverResourceExecutionModeTest {

    @Test
    void localExecutionEnabledOnlyInDevProfile() {
        SolverResource resource = new SolverResource();
        resource.runLocally = true;
        resource.activeProfile = "dev";

        assertTrue(resource.isLocalExecutionEnabled());

        resource.activeProfile = "prod";
        assertFalse(resource.isLocalExecutionEnabled());

        resource.runLocally = false;
        resource.activeProfile = "dev";
        assertFalse(resource.isLocalExecutionEnabled());
    }

    @Test
    void forceCloudTasksInNormalModeEvenWhenRunLocallyIsTrue() {
        TestableSolverResource resource = new TestableSolverResource();
        resource.runLocally = true;
        resource.activeProfile = "prod";
        resource.jobExecutionService = new StubJobExecutionService();

        var response = resource.triggerJob(minimalValidRequest());

        assertEquals(200, response.getStatus());
        assertTrue(resource.cloudTaskCalled);
    }

    private PlanningRequest minimalValidRequest() {
        PlanningRequest.ShiftInfo shift = new PlanningRequest.ShiftInfo(
                "shift-1",
                "D",
                "Day",
                LocalTime.of(9, 0),
                LocalTime.of(18, 0));

        PlanningRequest.OrganizationInfo organization = new PlanningRequest.OrganizationInfo(
                "org-1",
                "org-name",
                "HOSPITAL",
                List.of(shift),
                null,
                0,
                null,
                0);

        PlanningRequest.EmployeeInfo employee = new PlanningRequest.EmployeeInfo(
                "emp-1",
                "Alice",
                Set.of("shift-1"),
                Set.of());

        PlanningRequest.RequirementInfo requirement = new PlanningRequest.RequirementInfo("shift-1", 0, 1);

        return new PlanningRequest(
                organization,
                List.of(employee),
                List.of(),
                List.of(),
                List.of(requirement));
    }

    private static final class TestableSolverResource extends SolverResource {
        boolean cloudTaskCalled;

        @Override
        void createCloudTask(PlanningRequest requestDto, String executionId) {
            cloudTaskCalled = true;
        }

        @Override
        LaunchMode currentLaunchMode() {
            return LaunchMode.NORMAL;
        }
    }

    private static final class StubJobExecutionService extends JobExecutionService {
        @Override
        public String create(PlanningRequest request) {
            return "execution-id";
        }
    }
}
