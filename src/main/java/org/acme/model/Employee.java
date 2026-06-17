package org.acme.model;

import org.optaplanner.core.api.domain.lookup.PlanningId;

import java.util.HashSet;
import java.util.Set;

public class Employee {
    @PlanningId
    String id;

    String name;

    Set<String> skillSet;

    Set<String> availableShift;

    int yearlyNightWorkCount;
    int yearlyHolidayWorkCount;
    int yearlyOffRequestCount;
    int offRequestPenaltyWeight = 1;
    String preceptorId;


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

    public int getYearlyNightWorkCount() {
        return yearlyNightWorkCount;
    }

    public void setYearlyNightWorkCount(int yearlyNightWorkCount) {
        this.yearlyNightWorkCount = Math.max(0, yearlyNightWorkCount);
    }

    public int getYearlyHolidayWorkCount() {
        return yearlyHolidayWorkCount;
    }

    public void setYearlyHolidayWorkCount(int yearlyHolidayWorkCount) {
        this.yearlyHolidayWorkCount = Math.max(0, yearlyHolidayWorkCount);
    }

    public int getYearlyOffRequestCount() {
        return yearlyOffRequestCount;
    }

    public void setYearlyOffRequestCount(int yearlyOffRequestCount) {
        this.yearlyOffRequestCount = Math.max(0, yearlyOffRequestCount);
    }

    public int getOffRequestPenaltyWeight() {
        return offRequestPenaltyWeight;
    }

    public void setOffRequestPenaltyWeight(int offRequestPenaltyWeight) {
        this.offRequestPenaltyWeight = Math.max(1, offRequestPenaltyWeight);
    }

    @Override
    public String toString() {
        return "Employee{" +
                "name='" + name + '\'' +
                ", id='" + id + '\'' +
                '}';
    }

    public String getPreceptorId() {
        return preceptorId;
    }

    public void setPreceptorId(String preceptorId) {
        this.preceptorId = preceptorId;
    }
}
