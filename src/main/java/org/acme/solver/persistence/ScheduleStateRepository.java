package org.acme.solver.persistence;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.solver.model.ScheduleState;

@ApplicationScoped
public class ScheduleStateRepository implements PanacheRepository<ScheduleState> {

}
