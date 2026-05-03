package org.acme.model;

import org.optaplanner.core.api.domain.lookup.PlanningId;

import java.time.LocalDate;

public class Availability {

    @PlanningId
    Long id;

    Employee employee;

    LocalDate date;

    AvailabilityType availabilityType;

    public Availability() {
    }

    public Availability(Employee employee, LocalDate date, AvailabilityType availabilityType) {
        this.employee = employee;
        this.date = date;
        this.availabilityType = availabilityType;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Employee getEmployee() {
        return employee;
    }

    public void setEmployee(Employee employee) {
        this.employee = employee;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate localDate) {
        this.date = localDate;
    }

    public AvailabilityType getAvailabilityType() {
        return availabilityType;
    }

    public void setAvailabilityType(AvailabilityType availabilityType) {
        this.availabilityType = availabilityType;
    }

    @Override
    public String toString() {
        return availabilityType + "(" + employee + ", " + date + ")";
    }
}
