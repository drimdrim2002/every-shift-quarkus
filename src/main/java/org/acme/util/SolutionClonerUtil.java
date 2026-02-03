package org.acme.util;

import org.acme.model.EmployeeSchedule;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class SolutionClonerUtil {

    private final ObjectMapper objectMapper;

    @Inject
    public SolutionClonerUtil(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public EmployeeSchedule cloneSolution(EmployeeSchedule original) {
        if (original == null) {
            return null;
        }
        try {
            return objectMapper.readValue(objectMapper.writeValueAsString(original), EmployeeSchedule.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to clone solution", e);
        }
    }
}
