package org.acme.model;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import org.optaplanner.core.api.domain.lookup.PlanningId;

import java.util.HashSet;
import java.util.Set;

@Entity
public class Employee {
    @Id
    @PlanningId
    String id;

    String name;

    @ElementCollection(fetch = FetchType.EAGER)
    Set<String> skillSet;

    @ElementCollection(fetch = FetchType.EAGER)
    Set<String> availableShift;


    public Employee() {

    }

    public Employee(String id, String name, Set<String> availableShift) {
        this.id = id;
        this.name = name;
        this.availableShift = availableShift;
        this.skillSet = new HashSet<>();
    }


    public Employee(String id, String name, Set<String> availableShift, Set<String> skillSet) {
        this.id = id;
        this.name = name;
        this.availableShift = availableShift;
        this.skillSet = skillSet;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Set<String> getSkillSet() {
        return skillSet;
    }

    public void setSkillSet(Set<String> skillSet) {
        this.skillSet = skillSet;
    }

    public Set<String> getAvailableShift() {
        return availableShift;
    }

    public void setAvailableShift(Set<String> availableShift) {
        this.availableShift = availableShift;
    }

    @Override
    public String toString() {
        return id;
    }
}
