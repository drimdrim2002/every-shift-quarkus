package org.acme.model;

import java.util.List;

import org.acme.solver.core.RosterScore;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

public class EmployeeSchedule {
    List<Availability> availabilityList;

    List<Employee> employeeList;

    List<Shift> shiftList;

    RosterScore score;

    ScheduleState scheduleState;

    public EmployeeSchedule() {
    }

    public EmployeeSchedule(ScheduleState scheduleState, List<Availability> availabilityList, List<Employee> employeeList, List<Shift> shiftList) {
        this.scheduleState = scheduleState;
        this.availabilityList = availabilityList;
        this.employeeList = employeeList;
        this.shiftList = shiftList;
    }

    public ScheduleState getScheduleState() {
        return scheduleState;
    }

    public void setScheduleState(ScheduleState scheduleState) {
        this.scheduleState = scheduleState;
    }

    public List<Availability> getAvailabilityList() {
        return availabilityList;
    }

    public void setAvailabilityList(List<Availability> availabilityList) {
        this.availabilityList = availabilityList;
    }

    public List<Employee> getEmployeeList() {
        return employeeList;
    }

    public void setEmployeeList(List<Employee> employeeList) {
        this.employeeList = employeeList;
    }

    public List<Shift> getShiftList() {
        return shiftList;
    }

    public void setShiftList(List<Shift> shiftList) {
        this.shiftList = shiftList;
    }

    @JsonIgnore
    public RosterScore getScore() {
        return score;
    }

    @JsonIgnore
    public void setScore(RosterScore score) {
        this.score = score;
    }

    /** 저장된 result JSON의 기존 문자열 점수 계약을 유지합니다. */
    @JsonProperty("score")
    public String getSerializedScore() {
        return score == null ? null : score.toString();
    }
}
