package org.acme.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.acme.api.dto.PlanningRequest;
import org.acme.model.EmployeeSchedule;
import org.acme.model.ExecutionStatus;
import org.acme.model.JobExecution;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.optaplanner.core.api.score.buildin.bendable.BendableScore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.SetOptions;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.annotation.PostConstruct;

/**
 * Cloud Firestore를 사용한 Job Execution 상태 관리 서비스
 */
@ApplicationScoped
@Unremovable
public class JobExecutionService {

    private static final Logger LOG = LoggerFactory.getLogger(JobExecutionService.class);
    private static final String DEFAULT_COLLECTION_NAME = "job-executions";

    @Inject
    Firestore firestore;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    @ConfigProperty(name = "gcp.firestore.collection")
    Optional<String> configuredCollectionName;

    String collectionName;

    @PostConstruct
    void init() {
        String rawCollectionName = configuredCollectionName.orElse(DEFAULT_COLLECTION_NAME);
        String normalizedCollectionName = rawCollectionName == null ? "" : rawCollectionName.trim();

        if (normalizedCollectionName.isEmpty()) {
            LOG.warn("gcp.firestore.collection is blank. Fallback to default collection '{}'.", DEFAULT_COLLECTION_NAME);
            this.collectionName = DEFAULT_COLLECTION_NAME;
        } else {
            this.collectionName = normalizedCollectionName;
        }

        LOG.info("JobExecutionService initialized: firestoreCollection={}", this.collectionName);
    }

    /**
     * 새로운 Job Execution 문서를 생성하고 executionId를 반환
     */
    public String create(PlanningRequest request) {
        String executionId = java.util.UUID.randomUUID().toString();
        long now = Instant.now().toEpochMilli();

        try {
            Map<String, Object> createFields = new HashMap<>();
            createFields.put("id", executionId);
            createFields.put("tenantId", request.organization().id());
            createFields.put("organizationName", request.organization().name());
            createFields.put("status", ExecutionStatus.PENDING);
            createFields.put("createdAt", now);
            createFields.put("requestJson", objectMapper.writeValueAsString(request));

            firestore.collection(collectionName)
                    .document(executionId)
                    .set(createFields, SetOptions.merge())
                    .get();

            LOG.info("JobExecution created: id={}, organization={}", executionId, request.organization().name());
            return executionId;

        } catch (Exception e) {
            LOG.error("Failed to create JobExecution", e);
            throw new RuntimeException("Failed to create JobExecution: " + e.getMessage(), e);
        }
    }

    /**
     * ID로 Job Execution 문서 조회
     */
    public Optional<JobExecution> findById(String id) {
        try {
            var docSnapshot = firestore.collection(collectionName)
                    .document(id)
                    .get()
                    .get();

            if (!docSnapshot.exists()) {
                return Optional.empty();
            }

            JobExecution job = docSnapshot.toObject(JobExecution.class);
            job.setId(id); // Document ID를 명시적으로 설정
            return Optional.of(job);

        } catch (Exception e) {
            LOG.error("Failed to fetch JobExecution: id={}", id, e);
            throw new RuntimeException("Failed to fetch JobExecution: " + e.getMessage(), e);
        }
    }

    /**
     * Job Execution 상태 업데이트
     */
    public void updateStatus(String id, ExecutionStatus status) {
        try {
            long now = Instant.now().toEpochMilli();
            Map<String, Object> updates = new HashMap<>();
            updates.put("status", status);

            if (status == ExecutionStatus.RUNNING) {
                updates.put("startedAt", now);
            } else if (status == ExecutionStatus.COMPLETED || status == ExecutionStatus.FAILED) {
                updates.put("completedAt", now);
            }

            firestore.collection(collectionName)
                    .document(id)
                    .update(updates)
                    .get();

            LOG.debug("JobExecution status updated: id={}, status={}", id, status);

        } catch (Exception e) {
            LOG.error("Failed to update JobExecution status: id={}, status={}", id, status, e);
            throw new RuntimeException("Failed to update status: " + e.getMessage(), e);
        }
    }

    /**
     * Solver 실행 결과 저장
     */
    public void saveResult(String id, EmployeeSchedule solution) {
        try {
            long now = Instant.now().toEpochMilli();
            Map<String, Object> updates = new HashMap<>();
            updates.put("status", ExecutionStatus.COMPLETED);
            updates.put("completedAt", now);
            putScoreFields(updates, solution.getScore());
            updates.put("resultJson", serializeSolution(solution));

            firestore.collection(collectionName)
                    .document(id)
                    .update(updates)
                    .get();

            LOG.info("JobExecution result saved: id={}, score={}", id, solution.getScore());

        } catch (Exception e) {
            LOG.error("Failed to save JobExecution result: id={}", id, e);
            throw new RuntimeException("Failed to save result: " + e.getMessage(), e);
        }
    }

    /**
     * Solver 중간 실행 결과 저장 (Status: RUNNING 유지)
     */
    public void saveIntermediateResult(String id, EmployeeSchedule solution) {
        try {
            Map<String, Object> updates = new HashMap<>();
            updates.put("status", ExecutionStatus.RUNNING);
            putScoreFields(updates, solution.getScore());
            updates.put("resultJson", serializeSolution(solution));

            firestore.collection(collectionName)
                    .document(id)
                    .update(updates)
                    .get();

            LOG.info("Intermediate result saved: id={}, score={}", id, solution.getScore());

        } catch (Exception e) {
            LOG.error("Failed to save intermediate result: id={}", id, e);
            // 중간 저장 실패는 치명적이지 않으므로 로그만 남기고 진행할 수도 있지만,
            // 현행 로직 일관성을 위해 예외 발생
            throw new RuntimeException("Failed to save intermediate result: " + e.getMessage(), e);
        }
    }

    /**
     * 에러 메시지 저장
     */
    public void saveError(String id, String errorMessage) {
        try {
            long now = Instant.now().toEpochMilli();
            Map<String, Object> updates = new HashMap<>();
            updates.put("status", ExecutionStatus.FAILED);
            updates.put("completedAt", now);
            updates.put("errorMessage", errorMessage);

            firestore.collection(collectionName)
                    .document(id)
                    .update(updates)
                    .get();

            LOG.error("JobExecution failed: id={}, error={}", id, errorMessage);

        } catch (Exception e) {
            LOG.error("Failed to save JobExecution error: id={}", id, e);
            throw new RuntimeException("Failed to save error: " + e.getMessage(), e);
        }
    }

    /**
     * EmployeeSchedule을 JSON 문자열로 직렬화
     */
    private String serializeSolution(EmployeeSchedule solution) {
        try {
            return objectMapper.writeValueAsString(solution);
        } catch (Exception e) {
            LOG.error("Failed to serialize solution", e);
            return null;
        }
    }

    static Map<String, Object> extractScoreFields(BendableScore score) {
        Map<String, Object> scoreFields = new HashMap<>();
        scoreFields.put("hardScore", score.hardScore(0));
        scoreFields.put("night48RestSoftScore", score.softScore(0));
        scoreFields.put("night32RestSoftScore", score.softScore(1));
        scoreFields.put("undesiredSoftScore", score.softScore(2));
        scoreFields.put("fairSoftScore", score.softScore(3));
        scoreFields.put("desiredSoftScore", score.softScore(4));
        return scoreFields;
    }

    private void putScoreFields(Map<String, Object> updates, BendableScore score) {
        updates.putAll(extractScoreFields(score));
    }

    /**
     * PENDING 상태의 모든 Job Execution 조회 (관리용)
     */
    public java.util.List<JobExecution> findByStatus(ExecutionStatus status) {
        try {
            var snapshot = firestore.collection(collectionName)
                    .whereEqualTo("status", status)
                    .get()
                    .get();

            return snapshot.getDocuments().stream()
                    .map(doc -> {
                        JobExecution job = doc.toObject(JobExecution.class);
                        job.setId(doc.getId());
                        return job;
                    })
                    .toList();

        } catch (Exception e) {
            LOG.error("Failed to fetch JobExecutions by status: {}", status, e);
            throw new RuntimeException("Failed to fetch by status: " + e.getMessage(), e);
        }
    }
}
