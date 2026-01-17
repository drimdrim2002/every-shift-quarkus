package org.acme.model;

import jakarta.persistence.*;
import org.optaplanner.core.api.domain.entity.PlanningEntity;
import org.optaplanner.core.api.domain.lookup.PlanningId;
import org.optaplanner.core.api.domain.variable.PlanningVariable;

import java.time.LocalDateTime;

@Entity
@PlanningEntity(pinningFilter = ShiftPinningFilter.class)
public class Shift {
    @Id
    @PlanningId
    @GeneratedValue
    Long id;

    LocalDateTime start;
    @Column(name = "endDateTime") // "end" clashes with H2 syntax.
    LocalDateTime end;

    String location;
    String requiredSkill;

    @PlanningVariable
    @ManyToOne
    Employee employee;

    boolean pinned;

    public Shift() {
    }

    String supabaseId;

    public String getSupabaseId() {
        return supabaseId;
    }

    public Shift(Long id, String supabaseId, LocalDateTime start, LocalDateTime end, String location, String requiredSkill, Employee employee) {
        this(id, supabaseId, start, end, location, requiredSkill, employee, false);
    }

    public Shift(Long id, String supabaseId, LocalDateTime start, LocalDateTime end, String location, String requiredSkill, Employee employee, boolean pinned) {
        this.id = id;
        this.supabaseId = supabaseId;
        this.start = start;
        this.end = end;
        this.location = location;
        this.requiredSkill = requiredSkill;
        this.employee = employee;
        this.pinned = pinned;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDateTime getStart() {
        return start;
    }

    public void setStart(LocalDateTime start) {
        this.start = start;
    }

    public LocalDateTime getEnd() {
        return end;
    }

    public void setEnd(LocalDateTime end) {
        this.end = end;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getRequiredSkill() {
        return requiredSkill;
    }

    public void setRequiredSkill(String requiredSkill) {
        this.requiredSkill = requiredSkill;
    }

    public Employee getEmployee() {
        return employee;
    }

    public void setEmployee(Employee employee) {
        this.employee = employee;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    @Override
    public String toString() {
        return "Shift{" +
                "id=" + id +
                ", start=" + start +
                ", end=" + end +
                ", employee=" + employee +
                ", pinned=" + pinned +
                '}';
    }
}
