package org.acme.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import org.acme.model.EmployeeSchedule;
import org.acme.solver.core.RosterScore;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class EmployeeScheduleExternalContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void resultJson의_score는_기존_문자열_형식을_유지한다() throws Exception {
        EmployeeSchedule schedule = new EmployeeSchedule();
        schedule.setAvailabilityList(List.of());
        schedule.setEmployeeList(List.of());
        schedule.setShiftList(List.of());
        schedule.setScore(RosterScore.of(0, -30, -120, -5409, 240));

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(schedule));

        assertEquals("[0]hard/[-30/-120/-5409/240]soft", json.path("score").asText());
        assertFalse(json.path("score").isObject());
    }
}
