package org.acme.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import org.acme.api.dto.StatusResponse;
import org.acme.model.ExecutionStatus;
import org.acme.model.JobExecution;
import org.acme.solver.core.RosterScore;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class ScoreContractSnapshotTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void firestoreAndApiScoreProjectionMatchesGoldenSnapshot() throws Exception {
        // soft: undesired / fairness / desired / reserved
        RosterScore score = RosterScore.of(0, -120, -5409, 240, 0);

        Map<String, Object> firestoreFields = JobExecutionService.extractScoreFields(score);
        JobExecution job = completedJob(firestoreFields);
        StatusResponse apiResponse = StatusResponse.from(job, objectMapper);

        ObjectNode actual = objectMapper.createObjectNode();
        actual.put("schema_version", 1);
        actual.set("score_layout", scoreLayout());
        actual.set("firestore", objectMapper.valueToTree(new LinkedHashMap<>(firestoreFields)));
        actual.set("api", objectMapper.valueToTree(apiResponse));

        assertEquals(loadSnapshot(), actual);
    }

    private ObjectNode scoreLayout() {
        ObjectNode layout = objectMapper.createObjectNode();
        layout.put("hard_levels", 1);
        layout.put("soft_levels", 4);
        layout.putArray("soft_order")
                .add("undesired")
                .add("fairness")
                .add("desired")
                .add("reserved");
        return layout;
    }

    private JobExecution completedJob(Map<String, Object> fields) {
        JobExecution job = new JobExecution();
        job.setId("score-contract-snapshot");
        job.setTenantId("tenant-contract");
        job.setOrganizationName("organization-contract");
        job.setStatus(ExecutionStatus.COMPLETED);
        job.setHardScore((Integer) fields.get("hardScore"));
        job.setNight48RestSoftScore((Integer) fields.get("night48RestSoftScore"));
        job.setNight32RestSoftScore((Integer) fields.get("night32RestSoftScore"));
        job.setUndesiredSoftScore((Integer) fields.get("undesiredSoftScore"));
        job.setThreeConsecutiveNightSoftScore((Integer) fields.get("threeConsecutiveNightSoftScore"));
        job.setFairnessSoftScore((Integer) fields.get("fairnessSoftScore"));
        job.setDesiredSoftScore((Integer) fields.get("desiredSoftScore"));
        job.setBurdenFairnessSoftScore((Integer) fields.get("burdenFairnessSoftScore"));
        job.setFairSoftScore((Integer) fields.get("fairSoftScore"));
        return job;
    }

    private JsonNode loadSnapshot() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/contracts/score-contract-1h4s.json")) {
            if (input == null) {
                throw new IllegalStateException("점수 계약 snapshot 리소스를 찾을 수 없습니다.");
            }
            return objectMapper.readTree(input);
        }
    }
}
