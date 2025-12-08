package org.acme.solver.persistence;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.solver.model.Shift;

@ApplicationScoped
public class ShiftRepository implements PanacheRepository<Shift> {

}
